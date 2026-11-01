# Adoção de preparo de rascunho

## Contrato neutro e jornada

`AbstractDuplicateDraftResourceController<ResponseDTO, ID, FilterDTO, CreateDTO,
UpdateDTO, DraftDTO>` estende a hierarquia resource-oriented. Seu serviço
`DuplicateDraftResourceService<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO,
DraftDTO>` agrega `DuplicateDraftResourceCommandService<ID, DraftDTO>`; o serviço
pode usar JPA ou um adapter privado. Não usar as antigas bases públicas
`LegacyBacked*` como orientação para recursos novos ou migrados.

O `POST /{resource}/{id}/duplicate-draft` é uma preparação ITEM não persistente
e não mutante. Retorna `RestApiResponse<DraftDTO>`; não cria registro, reserva
chave, modifica a origem ou executa os efeitos do create. O DTO explícito é
compatível com os campos de criação, mas pode ser parcial. A porta não impõe um
nome de classe `DuplicateDraftDTO` nem exige reutilizar o request obrigatório.
O backend aplica a política de cópia por allowlist, limpando identidade, versão,
campos únicos, pessoais/protegidos e estado conforme o domínio. Não depender de
`DeepCloneUtils`, `DuplicateDraftUtils` ou de um atributo `copyOnDuplicate`:
esses não são contratos deste fluxo.

O consumidor abre CREATE com seu schema de request e o draft como valor inicial.
Somente revisão/confirmação envia o `POST /{resource}` separado, que revalida
autorização e regras. Descartar o draft não exige um endpoint mutante de cancel.
Clonar imediatamente um agregado e suas coleções `1:N` é outro comando de
negócio transacional, modelado por `@WorkflowAction` com suas próprias guardas.
A base de duplicate-draft também mantém `@WorkflowAction(id="duplicate-draft")`
para discovery: essa annotation real não torna o preparo persistente e não deve
ser removida por confusão terminológica com deep clone.

Suporte estrutural exige opt-in do serviço e mapping POST publicado. Disponibilidade
contextual exige decisões afirmativas de create COLLECTION, view ITEM da origem
e duplicate-draft ITEM, além das restrições da action. Ausência/falha/nulidade na
composição não autoriza duplicação. Capabilities, catálogo contextual, links e
execução devem concordar, sem fazer do discovery um substituto da guarda do host.

No Angular, o owner é `@praxisui/crud`: segue `_links['duplicate-draft']`, prepara
uma única abertura e passa um seed independente para `@praxisui/dynamic-form`.
Um objeto `initialValue` create, inclusive `{}`, suprime defaults omitidos mesmo
em rebuild; sem objeto seed o create ordinário mantém defaults. Não copiar a
política do backend para a UI nem abrir formulário vazio em erro. Create com seed
em rota requer `createCrudFormRoute`; edit/view do host mantém seu fluxo de leitura.
O handoff privado CRUD é efêmero e single-use, sem payload em URL/history/storage.

## Decisão de domínio e schema

O dono do contrato é o Metadata Starter. Antes de adotar `duplicate-draft`,
registre a decisão do domínio: campos sugeridos por allowlist, campos limpos,
defaults deliberados, estados de origem permitidos, escopo e autorização. Uma
capacidade estrutural ou um botão legado não substitui essa decisão. Consulte
[suporte estrutural](../technical/DUPLICATE-DRAFT-STRUCTURAL-SUPPORT.md) e
[disponibilidade](../technical/DUPLICATE-DRAFT-AVAILABILITY.md).

Use a porta `DuplicateDraftResourceService` e o controller opt-in
`AbstractDuplicateDraftResourceController` da coordenada efetivamente consumida.
O `DraftDTO` é independente de `CreateDTO`: quando o preparo deixa campos em aberto,
publique essa nulabilidade no schema de resposta, sem tornar opcional o que é
obrigatório no POST final. Teste o JSON e os schemas OpenAPI nas versões suportadas.
Para uma escolha booleana deliberada, `null` pode representar ausência de resposta;
`false` explícito continua sendo resposta válida. Não universalize esse default:
ele depende da decisão de domínio. Não crie um marcador privado de origem para
enfraquecer a validação de criação.

Metadados de apresentação presentes no schema de resposta também são contrato
público: referências e escolhas devem publicar controles, fontes e tipos
deliberados. Compare o schema materializado com o CREATE para detectar inferências
incorretas, preservando a diferença de nulabilidade. O consumidor continua usando
o schema de request do CREATE com os valores do draft; não crie outro formulário
a partir do schema de resposta para corrigir essa divergência editorial.

Availability, actions e `_links` devem concordar com as guardas efetivas do serviço,
incluindo origem, estado e escopo. Valide o contexto de entrada na fronteira HTTP
apropriada; um contexto malformado não deve ser reinterpretado como decisão de
negócio. A composição canônica de availability continua falhando fechada.

## Provas exigidas

- Compare campos sugeridos, limpos e não autorizados com uma origem rica; prove
  negações de origem inexistente/inacessível, estado e escopo sem vazar dados.
  Nas respostas de discovery, exija o status HTTP esperado e a estrutura JSON
  antes de verificar uma negação: campo ausente não equivale a booleano `false`,
  e envelope de erro não prova ausência correta de link. Para sigilo de estado,
  inspecione os metadados efetivos da decisão, distinguindo-os dos estados
  permitidos documentais; a busca por uma palavra que a fixture não contém não
  comprova ausência de vazamento.
- Demonstre ausência de escrita, reserva, mudança de versão e ledger; inclua o
  caso de contexto de persistência chamador com alterações pendentes. `readOnly`
  ou ausência de `save` no método, isoladamente, não prova ausência de flush.
- Revalide no POST final autorização, identidade, invariantes e campos protegidos.
  Derive as expectativas dos testes da configuração real de desserialização e do
  catálogo contextual. Teste cada propriedade desconhecida separadamente e isole
  campos explicitamente ignorados pelo DTO. Uma rejeição de payload obrigatório
  pode anteceder a guarda de domínio; prepare uma entrada válida para provar
  essa segunda guarda, sem relaxar configuração ou catálogo para satisfazer a fixture.
  Com chave atribuída, prove que entidade nova é reconhecida como nova pela camada
  de persistência; uma consulta prévia de existência não elimina corrida nem
  impede um merge. Combine regressão determinística dessa classificação com
  concorrência HTTP, conflito estruturado e leitura posterior sem overwrite.
- Registre fonte, commit/delta, coordenada, hashes do artefato e comandos/resultados.
  Um override privado não comprova consumo da dependência publicada no POM.
- Depois de reconciliar worktrees, teste o estado efetivamente adotado. Preserve
  diferenças locais legítimas e vincule o recibo ao código final. Não transfira
  automaticamente o resultado de uma árvore isolada para outro checkout.

Um inventário deve declarar o gate que sustenta o status de implementação:
decisão atribuída, código adotado no checkout e provas revisadas. Um aceite
estático/local autorizado pode registrar implementação local, mantendo o gate
de runtime separado e pendente; não significa integração executada. O hash do parecer comprova
sua integridade; não certifica runtime. Preserve decisões históricas e mantenha
separados aceite backend, runtime Angular/browser e publicação. Não classifique
como não aplicável uma jornada aplicável apenas para obter um gate verde.
Preserve campanhas reprovadas como evidências próprias. Uma falha fechada por
indisponibilidade não prova o comportamento funcional esperado, e uma repetição
bem-sucedida não demonstra que sua causa foi corrigida. Não amplie os status
aceitos pelo teste nem repita execuções até obter verde para encerrar a adoção.
