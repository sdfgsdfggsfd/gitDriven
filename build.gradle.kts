// Conventions shared by every plugin in plugins/<name>/.
// Each plugin produces build/libs/<name>.jar, and `collectServerPlugins` gathers them all
// into build/server-plugins/ — that's where Core copies the jars to the server from.

val foliaApiVersion: String by project
val minecraftApiVersion: String by project

// Core passes the commit being built in this variable; locally it's "dev".
val buildVersion = providers.environmentVariable("GIT_DRIVEN_COMMIT")
    .map { it.take(7) }
    .getOrElse("dev")

val pluginProjects = subprojects.filter { it.parent?.path == ":plugins" }

configure(pluginProjects) {
    apply(plugin = "java")

    group = "dev.gitdriven"
    version = buildVersion

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    dependencies {
        "compileOnly"("dev.folia:folia-api:$foliaApiVersion")
        // Tests (optional): JUnit 5 with the Folia API on the classpath.
        "testImplementation"("dev.folia:folia-api:$foliaApiVersion")
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.named<Jar>("jar") {
        // Fixed name (no version) so the server replaces the old jar with the new one.
        archiveFileName.set("${project.name}.jar")
    }

    tasks.named<ProcessResources>("processResources") {
        val props = mapOf("version" to buildVersion, "apiVersion" to minecraftApiVersion)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
}

val collectServerPlugins = tasks.register<Sync>("collectServerPlugins") {
    group = "git-driven"
    description = "Gathers every plugin's jar into build/server-plugins (used by Core)."
    into(layout.buildDirectory.dir("server-plugins"))
    pluginProjects.forEach { from(it.tasks.named("jar")) }
}

tasks.register<Copy>("installLocal") {
    group = "git-driven"
    description = "Copies the plugins to run/plugins (local test server). Core is left out."
    from(collectServerPlugins)
    exclude("core.jar")
    into(layout.projectDirectory.dir("run/plugins"))
}
