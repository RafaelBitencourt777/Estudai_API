
package com.estudai.estudai_api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/quiz")
@CrossOrigin(origins = {
    "http://127.0.0.1:5500",
    "http://localhost:5500"
})
public class QuizController {

    private static final String GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/"
        + "gemini-3.5-flash-lite:generateContent";

    private static final int MAX_TENTATIVAS = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    @GetMapping("/status")
    public ResponseEntity<String> status() {
        return ResponseEntity.ok(
            "Endpoint de quiz do Estudai funcionando!"
        );
    }

    @GetMapping("/chave-configurada")
    public ResponseEntity<Map<String, Object>> chaveConfigurada() {
        String chave = System.getenv("GEMINI_API_KEY");

        return ResponseEntity.ok(Map.of(
            "configurada", chave != null && !chave.isBlank()
        ));
    }

    @GetMapping("/teste-gemini")
    public ResponseEntity<?> testeGemini() {
        try {
            String chave = obterChave();

            String corpo = """
                {
                  "contents": [{
                    "parts": [{
                      "text": "Responda em português: quanto é 5 vezes 4 mais 3?"
                    }]
                  }]
                }
                """;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_URL + "?key=" + chave))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();

            HttpResponse<String> resposta = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (resposta.statusCode() < 200
                    || resposta.statusCode() >= 300) {

                System.err.println(
                    "Erro no teste do Gemini. HTTP "
                    + resposta.statusCode()
                );

                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                        "erro", "O Gemini retornou um erro.",
                        "statusGemini", resposta.statusCode()
                    ));
            }

            JsonNode respostaJson = objectMapper.readTree(
                resposta.body()
            );

            String texto = extrairTextoGemini(respostaJson);

            if (texto == null || texto.isBlank()) {
                throw new IllegalArgumentException(
                    "O Gemini retornou uma resposta vazia."
                );
            }

            return ResponseEntity.ok(texto);

        } catch (Exception e) {
            registrarErro("teste do Gemini", e);

            return ResponseEntity.status(
                HttpStatus.INTERNAL_SERVER_ERROR
            ).body(Map.of(
                "erro", "Não foi possível testar o Gemini.",
                "detalhes", mensagemErro(e)
            ));
        }
    }

    @PostMapping("/gerar")
    public ResponseEntity<?> gerarQuiz(
            @Valid @RequestBody GerarQuizRequest dados) {

        try {
            String chave = obterChave();

            JsonNode quiz = gerarQuizComTentativas(
                dados.materia(),
                dados.dificuldade(),
                chave
            );

            return ResponseEntity.ok(quiz);

        } catch (IllegalStateException e) {
            registrarErro("geração do quiz", e);

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                    "erro", e.getMessage()
                ));

        } catch (Exception e) {
            registrarErro("geração do quiz", e);

            return ResponseEntity.status(
                HttpStatus.INTERNAL_SERVER_ERROR
            ).body(Map.of(
                "erro", "Não foi possível gerar o quiz.",
                "detalhes", mensagemErro(e)
            ));
        }
    }

    private JsonNode gerarQuizComTentativas(
            String materia,
            String dificuldade,
            String chave) throws Exception {

        String ultimoErro = "Resposta inválida do Gemini.";

        for (int tentativa = 1;
                tentativa <= MAX_TENTATIVAS;
                tentativa++) {

            try {
                System.out.println(
                    "Gerando quiz: " + materia + " - " + dificuldade
                    + " | tentativa " + tentativa + "/"
                    + MAX_TENTATIVAS
                );

                String prompt = criarPrompt(
                    materia,
                    dificuldade,
                    tentativa
                );

                String corpo = objectMapper.writeValueAsString(
                    Map.of(
                        "contents", new Object[] {
                            Map.of(
                                "parts", new Object[] {
                                    Map.of("text", prompt)
                                }
                            )
                        },
                        "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.4
                        )
                    )
                );

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL + "?key=" + chave))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corpo))
                    .build();

                HttpResponse<String> resposta = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                );

                int status = resposta.statusCode();

                if (status < 200 || status >= 300) {

                    System.err.println(
                        "Gemini retornou HTTP " + status
                        + " na tentativa " + tentativa
                        + "/" + MAX_TENTATIVAS
                    );

                    // Repete somente erros que podem ser temporários.
                    boolean erroTemporario =
                        status == 408
                        || status == 429
                        || status >= 500;

                    if (erroTemporario
                            && tentativa < MAX_TENTATIVAS) {

                        long esperaMs =
                            1000L * (1L << (tentativa - 1));

                        System.out.println(
                            "Erro temporário. Nova tentativa em "
                            + (esperaMs / 1000) + " segundo(s)."
                        );

                        Thread.sleep(esperaMs);
                        continue;
                    }

                    throw new IllegalStateException(
                        "O Gemini retornou HTTP " + status
                        + ". Verifique o serviço, a configuração "
                        + "e os limites da API."
                    );
                }

                // Converte a resposta HTTP em JSON.
                JsonNode jsonGemini = objectMapper.readTree(
                    resposta.body()
                );

                // Extrai o texto produzido pelo Gemini.
                String texto = extrairTextoGemini(jsonGemini);

                if (texto == null || texto.isBlank()) {
                    throw new IllegalArgumentException(
                        "O Gemini retornou uma resposta vazia."
                    );
                }

                // Converte o texto do quiz em um segundo objeto JSON.
                JsonNode quiz = objectMapper.readTree(
                    limparResposta(texto)
                );

                validarQuiz(quiz);

                System.out.println(
                    "Quiz gerado e validado com sucesso."
                );

                return quiz;

            } catch (JacksonException | IllegalArgumentException e) {

                ultimoErro = mensagemErro(e);

                System.err.println(
                    "Resposta inválida na tentativa "
                    + tentativa + "/" + MAX_TENTATIVAS
                    + ": " + e.getClass().getSimpleName()
                );

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;

            } catch (IllegalStateException e) {
                // Erros HTTP finais não são tratados como JSON inválido.
                throw e;

            } catch (Exception e) {
                ultimoErro = mensagemErro(e);

                System.err.println(
                    "Falha na tentativa " + tentativa
                    + "/" + MAX_TENTATIVAS
                    + ": " + e.getClass().getSimpleName()
                );
            }
        }

        throw new IllegalStateException(
            "Não foi possível gerar um quiz válido após "
            + MAX_TENTATIVAS + " tentativas. "
            + ultimoErro
        );
    }

    private String criarPrompt(
            String materia,
            String dificuldade,
            int tentativa) {

        String instrucaoExtra = tentativa > 1
            ? """
              
              IMPORTANTE: a resposta anterior não passou na validação.
              Gere o objeto novamente desde o início. Confira cada
              vírgula, aspas, colchete e chave. Não inclua comentários,
              Markdown nem texto fora do JSON.
              """
            : "";

        return """
            Gere um quiz educacional em português brasileiro.

            Matéria: %s
            Dificuldade: %s

            Crie exatamente 10 questões diferentes, adequadas à matéria
            e ao nível de dificuldade solicitados.

            Cada questão deve conter:
            - numero: número inteiro de 1 a 10;
            - pergunta: enunciado não vazio;
            - alternativas: lista de exatamente 4 textos;
            - respostaCorreta: texto idêntico a uma das alternativas;
            - topico: assunto específico avaliado.

            As perguntas devem ser claras, variadas e adequadas ao nível.
            Não repita a mesma pergunta dentro do quiz.
            Confira a exatidão factual de cada resposta correta.
            Não invente fatos históricos nem misture períodos ou eventos.

            Retorne somente JSON válido. Use aspas duplas em todas as
            chaves e strings. Não use comentários nem vírgulas sobrando.
            Não inclua Markdown ou explicações antes ou depois do JSON.

            Formato obrigatório:
            {
              "materia": "%s",
              "dificuldade": "%s",
              "questoes": [
                {
                  "numero": 1,
                  "pergunta": "Enunciado da questão",
                  "alternativas": [
                    "Alternativa A",
                    "Alternativa B",
                    "Alternativa C",
                    "Alternativa D"
                  ],
                  "respostaCorreta": "Alternativa correta",
                  "topico": "Nome do tópico"
                }
              ]
            }

            O array questoes deve conter exatamente 10 objetos.
            %s
            """.formatted(
                materia,
                dificuldade,
                materia,
                dificuldade,
                instrucaoExtra
            );
    }

    private String obterChave() {
        String chave = System.getenv("GEMINI_API_KEY");

        if (chave == null || chave.isBlank()) {
            throw new IllegalStateException(
                "A variável GEMINI_API_KEY não está configurada."
            );
        }

        return chave;
    }

    private String extrairTextoGemini(JsonNode jsonGemini) {
        JsonNode textoNode = jsonGemini.path("candidates")
            .path(0)
            .path("content")
            .path("parts")
            .path(0)
            .path("text");

        if (textoNode.isMissingNode()
                || textoNode.isNull()
                || !textoNode.isString()) {
            return null;
        }

        return textoNode.asString();
    }

    private String limparResposta(String texto) {
        String resultado = texto.trim();

        if (resultado.startsWith("```")) {
            resultado = resultado.replaceFirst(
                "^```(?:json)?\\s*",
                ""
            );

            resultado = resultado.replaceFirst(
                "\\s*```$",
                ""
            ).trim();
        }

        return resultado;
    }

    private void validarQuiz(JsonNode quiz) {
        if (!quiz.isObject()) {
            throw new IllegalArgumentException(
                "A resposta do quiz não é um objeto JSON."
            );
        }

        JsonNode questoes = quiz.path("questoes");

        if (!questoes.isArray() || questoes.size() != 10) {
            throw new IllegalArgumentException(
                "O quiz não contém exatamente 10 questões."
            );
        }

        for (int i = 0; i < questoes.size(); i++) {
            JsonNode questao = questoes.get(i);

            JsonNode pergunta = questao.path("pergunta");
            JsonNode alternativas = questao.path("alternativas");
            JsonNode respostaCorreta = questao.path("respostaCorreta");
            JsonNode topico = questao.path("topico");
            JsonNode numero = questao.path("numero");

            if (!numero.isIntegralNumber()
                    || numero.asInt() != i + 1
                    || !pergunta.isString()
                    || pergunta.asString().isBlank()
                    || !alternativas.isArray()
                    || alternativas.size() != 4
                    || !respostaCorreta.isString()
                    || respostaCorreta.asString().isBlank()
                    || !topico.isString()
                    || topico.asString().isBlank()) {

                throw new IllegalArgumentException(
                    "A questão " + (i + 1)
                    + " contém campos ausentes ou inválidos."
                );
            }

            boolean encontrouResposta = false;

            for (JsonNode alternativa : alternativas) {
                if (!alternativa.isString()
                        || alternativa.asString().isBlank()) {
                    throw new IllegalArgumentException(
                        "Uma alternativa está vazia ou não é texto."
                    );
                }

                if (alternativa.asString().equals(
                        respostaCorreta.asString())) {
                    encontrouResposta = true;
                }
            }

            if (!encontrouResposta) {
                throw new IllegalArgumentException(
                    "A resposta correta da questão " + (i + 1)
                    + " não corresponde a nenhuma das quatro alternativas."
                );
            }
        }
    }

    private String mensagemErro(Exception e) {
        return e.getMessage() == null
            ? "Sem detalhes adicionais."
            : e.getMessage();
    }

    private void registrarErro(String contexto, Exception e) {
        System.err.println(
            "Erro no " + contexto + ": "
            + e.getClass().getSimpleName()
        );

        System.err.println(
            "Detalhes: " + mensagemErro(e)
        );
    }
}
