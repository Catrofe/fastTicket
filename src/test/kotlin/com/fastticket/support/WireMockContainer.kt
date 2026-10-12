package com.fastticket.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path

class WireMockContainer : GenericContainer<WireMockContainer>(DockerImageName.parse("wiremock/wiremock:3.9.1")) {

    init {
        withCommand("--global-response-templating")
        withExposedPorts(PORT)
        mappingsDir().let { dir ->
            Files.list(dir).use { files ->
                files.filter { it.toString().endsWith(".json") }.forEach {
                    withCopyToContainer(
                        Transferable.of(Files.readAllBytes(it)),
                        "/home/wiremock/mappings/${it.fileName}",
                    )
                }
            }
        }
        waitingFor(Wait.forHttp("/__admin/health").forPort(PORT))
    }

    fun baseUrl(): String = "http://$host:${getMappedPort(PORT)}"

    private fun mappingsDir(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.isDirectory(dir.resolve("infra/wiremock/mappings"))) dir = dir.parent
        return requireNotNull(dir) { "infra/wiremock/mappings not found" }.resolve("infra/wiremock/mappings")
    }

    companion object {
        private const val PORT = 8080
    }
}
