import java.util.Properties

plugins {
    id("com.android.application")
}

// Реквизиты релизного ключа (ADR-047). Файл лежит вне репозитория, путь к нему -
// в переменной окружения ISTOK_SIGNING_PROPERTIES. Один и тот же механизм локально
// и в CI: CI восстанавливает keystore и этот файл из Secrets и ставит переменную.
// Переменной нет - release собирается неподписанным, как и раньше.
val signingProps: Properties? = System.getenv("ISTOK_SIGNING_PROPERTIES")?.let { path ->
    val f = File(path)
    if (f.isFile) Properties().apply { f.inputStream().use { stream -> load(stream) } } else null
}

android {
    namespace = "io.github.therebecore.istok"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.therebecore.istok"
        minSdk = 26                  // ADR-003: ниже недоступен WebView Safe Browsing
        targetSdk = 37          // ADR-061: Android 17, вместе с ним CT и ECH по умолчанию
        // Значения - в gradle.properties (ADR-049), потому что выпуск меняет данные,
        // а не логику сборки. Оттуда же их подставляют стенд и CI через -P.
        versionCode = (property("istokVersionCode") as String).toInt()
        versionName = property("istokVersionName") as String

        // Тестовый раннер не подключаем: инструментальных тестов в проекте нет,
        // а лишняя зависимость противоречит бюджету веса.
    }

    signingConfigs {
        signingProps?.let { p ->
            create("release") {
                storeFile = File(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Ключ один и тот же на все выпуски навсегда: сменить его - значит
            // лишить установленное приложение возможности обновиться (ADR-047).
            signingConfig = signingConfigs.findByName("release")
            // R8 включён с самого первого этапа намеренно: если подключать его
            // только перед релизом, всплывает пачка сюрпризов разом.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Критерий приёмки этапов - сборка без ошибок и предупреждений.
    // Чтобы это не осталось благим намерением, предупреждение ломает сборку.
    // Осознанные исключения - в app/lint.xml, каждое с обоснованием.
    lint {
        warningsAsErrors = true
    }

    packaging {
        resources {
            // Единственное исключение, и оно с замером: kotlin_builtins занимали
            // 12 КБ из 48 КБ release APK - больше четверти веса. Нужны они только
            // kotlin-reflect, которого в проекте нет и не будет (ADR-009).
            //
            // Шаблонных исключений Android Studio (META-INF от coroutines, DebugProbesKt)
            // здесь намеренно нет: зависимостей ноль, исключать нечего.
            excludes += "/kotlin/**"
        }
    }
}

// Зависимостей нет и это осознанно (ADR-009, docs/VISION.md принцип 1).
// Ни AppCompat, ни Material, ни Compose: приложение обходится классами
// самого Android SDK. Каждая будущая зависимость требует обоснования в ADR.
dependencies {
}
