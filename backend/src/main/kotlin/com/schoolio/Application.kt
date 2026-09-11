package com.schoolio

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import io.ktor.server.freemarker.*
import freemarker.cache.ClassTemplateLoader
import freemarker.core.HTMLOutputFormat
import freemarker.template.Configuration

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        setOutputFormat(HTMLOutputFormat.INSTANCE)
        autoEscapingPolicy = Configuration.ENABLE_IF_DEFAULT_AUTO_ESCAPING_POLICY
    }

    routing {
        staticResources("/", "static")

        get("/") {
            // K_SERVICE/K_REVISION are Cloud Run-only env vars (unset locally) -
            // showing the revision here is a quick way to confirm a given deploy
            // actually landed without digging into the Cloud Run console.
            val revision = System.getenv("K_REVISION")
            call.respond(FreeMarkerContent("splash.ftl", mapOf("revision" to revision)))
        }
    }
}
