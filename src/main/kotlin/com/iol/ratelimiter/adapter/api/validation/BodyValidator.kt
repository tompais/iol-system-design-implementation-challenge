package com.iol.ratelimiter.adapter.api.validation

import com.iol.ratelimiter.adapter.api.errors.exceptions.BadRequestException
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.Validator

class BodyValidator(
    private val validator: Validator,
) {
    fun <T : Any> validate(body: T) {
        val errors = BeanPropertyBindingResult(body, body::class.simpleName ?: "body")
        validator.validate(body, errors)
        if (errors.hasErrors()) throw BadRequestException(errors.allErrors.mapNotNull { it.defaultMessage })
    }
}
