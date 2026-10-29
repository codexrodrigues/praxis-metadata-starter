# Adoção de preparo de rascunho

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

Um inventário de adoção passa de pendente a implementada somente após decisão
atribuída, implementação integrada e provas revisadas. O hash do parecer comprova
sua integridade; não certifica runtime. Preserve decisões históricas e mantenha
separados aceite backend, runtime Angular/browser e publicação. Não classifique
como não aplicável uma jornada aplicável apenas para obter um gate verde.
Preserve campanhas reprovadas como evidências próprias. Uma falha fechada por
indisponibilidade não prova o comportamento funcional esperado, e uma repetição
bem-sucedida não demonstra que sua causa foi corrigida. Não amplie os status
aceitos pelo teste nem repita execuções até obter verde para encerrar a adoção.
