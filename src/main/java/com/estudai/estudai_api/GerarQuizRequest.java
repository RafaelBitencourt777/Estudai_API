
package com.estudai.estudai_api;

import jakarta.validation.constraints.NotBlank;

public record GerarQuizRequest(
    @NotBlank(message = "A matéria é obrigatória.")
    String materia,

    @NotBlank(message = "A dificuldade é obrigatória.")
    String dificuldade
) {
}
