plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenCentral()
}

dependencies {
    // GameProvider は fabric-loader の内部 API なので compileOnly。
    // 実行時は起動クラスパス上の fabric-loader を使う。
    compileOnly("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")

    // fabric-loader の POM は ASM を推移的に持ってこないので明示する。
    // fabric-loader が起動時に読むものと同じバージョンを使うこと
    // (クラスパス上に ASM が2つあると LoaderUtil.verifyClasspath が起動を止める)。
    compileOnly("org.ow2.asm:asm:${property("asm_version")}")
    compileOnly("org.ow2.asm:asm-tree:${property("asm_version")}")
}

// トップレベルのツールチェーンは指定しない(JDK 21 が入っていない環境でも
// JDK 21 以降があればビルドできるようにするため)。
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.jar {
    // dev.shifu.launcher(起動側)と dev.shifu.bootstrap(GameProvider)は同じ jar に入れる。
    // ランチャが子 JVM のクラスパスに自分自身を 1 エントリで載せるため。
    archiveBaseName = "shifu"

    manifest {
        attributes("Main-Class" to "dev.shifu.launcher.Main")
    }
}
