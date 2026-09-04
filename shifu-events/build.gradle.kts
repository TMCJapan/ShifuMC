plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

// ソースは src/event/java に置いてある。once.sh が Paper のツリーへ写してから
// 本番のコンパイルをするので、ここは IDE の補完と、写す前の型チェック用。
sourceSets.named("main") {
    java.setSrcDirs(listOf(rootProject.file("src/event/java")))
    resources.setSrcDirs(emptyList<String>())
}

// 発火層は Paper のサーバー jar に対してコンパイルする。組んだあとにしか無いので、
// 無ければこのプロジェクトのコンパイルは飛ばす。
val paperDir = providers.environmentVariable("SHIFU_PAPER")
    .map { file(it) }
    .orElse(providers.provider { rootProject.projectDir.parentFile.resolve(".pw") })

val serverJars = paperDir.map { pw ->
    val run = pw.resolve("run-shifu")
    val versions = run.resolve("versions")
    val jars = mutableListOf<File>()

    versions.walkTopDown().maxDepth(2).filterTo(jars) { it.isFile && it.name.endsWith(".jar") }
    run.resolve("libraries").walkTopDown().filterTo(jars) { it.isFile && it.name.endsWith(".jar") }

    jars.toList()
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files(serverJars))

    // アダプタ層の宣言が付けている注釈。サーバーの jar には入っていない。
    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"

    // 見ているのは前回 run-server.sh で組んだ jar なので、そのあとに patches/hand で
    // 足したメンバーはまだ無い。そのぶんのエラーが必ず出るので、既定では走らせない。
    //
    //     ./gradlew :shifu-events:compileJava -PeventsCheck
    //
    // 同じことを速くやるなら sh tools/javac-event.sh。
    // 本番のコンパイルは once.sh が Paper のツリーへ写して行う。
    onlyIf {
        if (!project.hasProperty("eventsCheck")) {
            return@onlyIf false
        }

        val ok = serverJars.get().isNotEmpty()

        if (!ok) {
            logger.lifecycle("サーバーの jar が無いので飛ばす(sh tools/run-server.sh で組む)")
        }

        ok
    }
}

// 成果物は使わない。once.sh がソースを写してビルドする。
tasks.named("jar") { enabled = false }
