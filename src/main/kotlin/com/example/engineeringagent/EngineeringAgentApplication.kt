package com.example.engineeringagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class EngineeringAgentApplication

fun main(args: Array<String>) {
    runApplication<EngineeringAgentApplication>(*args)
}
