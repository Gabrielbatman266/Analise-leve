plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.leve.analise"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.leve.analise"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Assinatura fixa vinda dos segredos do GitHub: cada APK novo instala por cima do antigo sem perder o banco.
    // Sem segredo, usa a chave de debug (funciona, mas não atualiza por cima de um APK assinado diferente).
    val ks = System.getenv("LEVE_KEYSTORE_PATH")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("leve") {
                storeFile = file(ks)
                storePassword = System.getenv("LEVE_KEYSTORE_PASS")
                keyAlias = System.getenv("LEVE_KEY_ALIAS") ?: "leve"
                keyPassword = System.getenv("LEVE_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("leve") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // edição de vídeo (exportar 1080p com texto, extrair áudio)
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-muxer:1.4.1")

    // testes da agenda (rodam no GitHub a cada build; se falhar, não sai APK)
    testImplementation("junit:junit:4.13.2")
}
