package com.fastticket.shared.validation

import com.fastticket.shared.error.ValidationException
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.method.support.HandlerMethodArgumentResolver as Resolver

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class BuyerCpf

const val BUYER_CPF_HEADER = "X-Buyer-Cpf"

@Component
class BuyerCpfArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(BuyerCpf::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val raw = webRequest.getHeader(BUYER_CPF_HEADER)
        val cpf = Cpf.parseOrNull(raw) ?: throw ValidationException("Header $BUYER_CPF_HEADER is missing or invalid")
        return if (parameter.parameterType == String::class.java) cpf.digits else cpf
    }
}

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
class BuyerCpfWebConfig(private val resolver: BuyerCpfArgumentResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<Resolver>) {
        resolvers.add(resolver)
    }
}
