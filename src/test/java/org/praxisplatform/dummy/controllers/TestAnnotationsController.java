package org.praxisplatform.dummy.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.praxisplatform.uischema.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("test")
@Tag(name = "Testes de Anotações", description = "Endpoints para testar anotações UISchema")
public class TestAnnotationsController {

    @GetMapping("all")
    @Operation(summary = "Obter formulário de teste", description = "Retorna um DTO com anotações UISchema para teste")
    public ResponseEntity<RestApiResponse<TestDTO>> getTestForm() {
        TestDTO dto = new TestDTO();
        dto.setNome("Exemplo de nome");
        dto.setSenha("123456");
        dto.setEmail("teste@exemplo.com");
        dto.setNumero(42);

        return ResponseEntity.ok(RestApiResponse.success(dto, null));
    }

    //Obter uma lista de exemplos de DTOs com anotações UISchema

    @GetMapping("listar-exemplos")
    @Operation(summary = "Listar exemplos de DTOs", description = "Retorna uma lista de exemplos de DTOs com anotações UISchema")
    public ResponseEntity<RestApiResponse<List<TestDTO>>> listExamples() {
        List<TestDTO> examples = List.of(
                new TestDTO("Exemplo 1", "senha1", "email1@exemplo.com", 10),
                new TestDTO("Exemplo 2", "senha2", "email2@exemplo.com", 20)
        );
        return ResponseEntity.ok(RestApiResponse.success(examples, null));
    }


    @PostMapping
    @Operation(summary = "Enviar formulário de teste", description = "Recebe um DTO com dados de teste")
    public ResponseEntity<RestApiResponse<String>> submitForm(@RequestBody TestDTO dto) {
        return ResponseEntity.ok(RestApiResponse.success("Formulário recebido com sucesso: " + dto.getNome(), null));
    }

    @GetMapping("list")
    @Operation(summary = "Listar exemplos", description = "Retorna uma lista de exemplos para testar")
    public ResponseEntity<RestApiResponse<List<TestDTO>>> getList() {
        List<TestDTO> examples = List.of(
                new TestDTO("Exemplo 1", "senha1", "email1@exemplo.com", 10),
                new TestDTO("Exemplo 2", "senha2", "email2@exemplo.com", 20)
        );
        return ResponseEntity.ok(RestApiResponse.success(examples, null));
    }

public static class TestDTO {
    @UISchema()
    private String nome;

    @UISchema()
    @Schema(
            description = "Senha de acesso do usuário",
            example = "S3nh@F0rt3",
            type = "string",
            minLength = 6,
            maxLength = 100,
            format = "password",
            accessMode = Schema.AccessMode.WRITE_ONLY,
            nullable = false,
            hidden = false,
            title = "Senha de Acesso"
    )
    private String senha;

    @UISchema(
            name = "email",
            label = "E-mail",
            type = FieldDataType.EMAIL,
            controlType = FieldControlType.INPUT,
            placeholder = "exemplo@email.com",
            required = true,
            pattern = ValidationPattern.EMAIL,
            requiredMessage = "O e-mail é obrigatório",
            order = 2
    )
    private String email;

    @UISchema(
            name = "numero",
            label = "Número",
            type = FieldDataType.NUMBER,
            controlType = FieldControlType.INPUT,
            required = true,
            order = 4
    )
    private Integer numero;

    @UISchema(
            name = "dataNascimento",
            label = "Data de Nascimento",
            type = FieldDataType.DATE,
            controlType = FieldControlType.DATE_PICKER,
            required = true,
            group = "Dados Pessoais"
    )
    private String dataNascimento;

    @UISchema(
            name = "cpf",
            label = "CPF",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.INPUT,
            placeholder = "000.000.000-00",
            pattern = ValidationPattern.CPF,
            mask = "000.000.000-00",
            group = "Documentos"
    )
    private String cpf;

    @UISchema(
            name = "telefone",
            label = "Telefone",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.PHONE,
            placeholder = "(00) 00000-0000",
            pattern = ValidationPattern.PHONE
    )
    private String telefone;

    @UISchema(
            name = "endereco",
            label = "Endereço",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.TEXTAREA,
            maxLength = 200,
            group = "Endereço"
    )
    private String endereco;

    @UISchema(
            name = "cep",
            label = "CEP",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.INPUT,
            pattern = ValidationPattern.CEP_BR,
            mask = "00000-000",
            group = "Endereço"
    )
    private String cep;

    @UISchema(
            name = "estado",
            label = "Estado",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.SELECT,
            options = "SP|São Paulo,RJ|Rio de Janeiro,MG|Minas Gerais,BA|Bahia",
            group = "Endereço",
            dependentField = "cidade"
    )
    private String estado;

    @UISchema(
            name = "cidade",
            label = "Cidade",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.SELECT,
            endpoint = "/api/cidades?estado=${estado}",
            disabled = true,
            group = "Endereço"
    )
    private String cidade;

    @UISchema(
            name = "salario",
            label = "Salário Pretendido",
            type = FieldDataType.NUMBER,
            controlType = FieldControlType.INPUT,
            numericFormat = NumericFormat.CURRENCY,
            numericMin = "1000",
            numericMax = "50000"
    )
    private Double salario;

    @UISchema(
            name = "habilidades",
            label = "Habilidades",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.MULTI_SELECT,
            multiple = true,
            options = "java|Java,python|Python,javascript|JavaScript,csharp|C#,cplusplus|C++"
    )
    private List<String> habilidades;

    @UISchema(
            name = "disponibilidade",
            label = "Disponibilidade",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.CHECKBOX,
            options = "manha|Manhã,tarde|Tarde,noite|Noite",
            multiple = true
    )
    private List<String> disponibilidade;

    @UISchema(
            name = "aceitaTermos",
            label = "Aceito os termos e condições",
            type = FieldDataType.BOOLEAN,
            controlType = FieldControlType.CHECKBOX,
            required = true
    )
    private Boolean aceitaTermos;

    @UISchema(
            name = "curriculo",
            label = "Currículo",
            type = FieldDataType.FILE,
            controlType = FieldControlType.FILE_UPLOAD,
            allowedFileTypes = AllowedFileTypes.PDF,
            maxFileSize = "5MB"
    )
    private String curriculo;

    @UISchema(
            name = "observacoes",
            label = "Observações",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.RICH_TEXT_EDITOR,
            maxLength = 1000
    )
    private String observacoes;

    @UISchema(
            name = "periodoDisponibilidade",
            label = "Período de Disponibilidade",
            type = FieldDataType.DATE,
            controlType = FieldControlType.DATE_RANGE
    )
    private String periodoDisponibilidade;

    @UISchema(
            name = "notificacoes",
            label = "Receber notificações",
            type = FieldDataType.BOOLEAN,
            controlType = FieldControlType.TOGGLE,
            defaultValue = "true"
    )
    private Boolean notificacoes;

    @UISchema(
            name = "idiomas",
            label = "Idiomas",
            type = FieldDataType.JSON,
            controlType = FieldControlType.TEXTAREA
    )
    private String idiomas;

    @UISchema(
            name = "nivelExperiencia",
            label = "Nível de Experiência",
            type = FieldDataType.TEXT,
            controlType = FieldControlType.SLIDER,
            numericMin = "0",
            numericMax = "10"
    )
    private String nivelExperiencia;

    @UISchema(
            name = "linkedin",
            label = "Perfil LinkedIn",
            type = FieldDataType.URL,
            controlType = FieldControlType.URL_INPUT,
            placeholder = "https://linkedin.com/in/seuperfil",
            pattern = ValidationPattern.URL
    )
    private String linkedin;

    @UISchema(
            name = "fotoPerfil",
            label = "Foto de Perfil",
            type = FieldDataType.FILE,
            controlType = FieldControlType.FILE_UPLOAD,
            allowedFileTypes = AllowedFileTypes.IMAGES,
            maxFileSize = "2MB"
    )
    private String fotoPerfil;

    // Construtores
    public TestDTO() {}

    public TestDTO(String nome, String senha, String email, Integer numero) {
        this.nome = nome;
        this.senha = senha;
        this.email = email;
        this.numero = numero;
    }

    // Getters e Setters
    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getSenha() {
        return senha;
    }

    public void setSenha(String senha) {
        this.senha = senha;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getNumero() {
        return numero;
    }

    public void setNumero(Integer numero) {
        this.numero = numero;
    }

    public String getDataNascimento() {
        return dataNascimento;
    }

    public void setDataNascimento(String dataNascimento) {
        this.dataNascimento = dataNascimento;
    }

    public String getCpf() {
        return cpf;
    }

    public void setCpf(String cpf) {
        this.cpf = cpf;
    }

    public String getTelefone() {
        return telefone;
    }

    public void setTelefone(String telefone) {
        this.telefone = telefone;
    }

    public String getEndereco() {
        return endereco;
    }

    public void setEndereco(String endereco) {
        this.endereco = endereco;
    }

    public String getCep() {
        return cep;
    }

    public void setCep(String cep) {
        this.cep = cep;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public String getCidade() {
        return cidade;
    }

    public void setCidade(String cidade) {
        this.cidade = cidade;
    }

    public Double getSalario() {
        return salario;
    }

    public void setSalario(Double salario) {
        this.salario = salario;
    }

    public List<String> getHabilidades() {
        return habilidades;
    }

    public void setHabilidades(List<String> habilidades) {
        this.habilidades = habilidades;
    }

    public List<String> getDisponibilidade() {
        return disponibilidade;
    }

    public void setDisponibilidade(List<String> disponibilidade) {
        this.disponibilidade = disponibilidade;
    }

    public Boolean getAceitaTermos() {
        return aceitaTermos;
    }

    public void setAceitaTermos(Boolean aceitaTermos) {
        this.aceitaTermos = aceitaTermos;
    }

    public String getCurriculo() {
        return curriculo;
    }

    public void setCurriculo(String curriculo) {
        this.curriculo = curriculo;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public void setObservacoes(String observacoes) {
        this.observacoes = observacoes;
    }

    public String getPeriodoDisponibilidade() {
        return periodoDisponibilidade;
    }

    public void setPeriodoDisponibilidade(String periodoDisponibilidade) {
        this.periodoDisponibilidade = periodoDisponibilidade;
    }

    public Boolean getNotificacoes() {
        return notificacoes;
    }

    public void setNotificacoes(Boolean notificacoes) {
        this.notificacoes = notificacoes;
    }

    public String getIdiomas() {
        return idiomas;
    }

    public void setIdiomas(String idiomas) {
        this.idiomas = idiomas;
    }

    public String getNivelExperiencia() {
        return nivelExperiencia;
    }

    public void setNivelExperiencia(String nivelExperiencia) {
        this.nivelExperiencia = nivelExperiencia;
    }

    public String getLinkedin() {
        return linkedin;
    }

    public void setLinkedin(String linkedin) {
        this.linkedin = linkedin;
    }

    public String getFotoPerfil() {
        return fotoPerfil;
    }

    public void setFotoPerfil(String fotoPerfil) {
        this.fotoPerfil = fotoPerfil;
    }
}
}
