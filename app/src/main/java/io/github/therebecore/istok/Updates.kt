package io.github.therebecore.istok

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Найденная новая версия: что показать человеку и откуда качать. */
internal class UpdateInfo(val versionCode: Int, val versionName: String, val url: String)

/**
 * Обновление по воздуху (ADR-051).
 *
 * **Это первый и единственный собственный сетевой запрос браузера.** Всё остальное, что
 * уходит в сеть, идёт по прямой просьбе человека - он открыл сайт. Здесь приложение
 * обращается наружу само, и поэтому у проверки есть выключатель ([Toggle.UPDATES]),
 * а требование B-3 переписано под это (`SECURITY.md`).
 *
 * **Устройство нарочно скучное:** по адресу лежит короткое описание последней сборки
 * (номер версии, имя, ссылка на APK), приложение читает его и сравнивает номер со своим.
 * Где именно лежит описание - подробность одного адреса: до открытия исходников это был
 * закрытый gist, с 2026-09-03 - страница последнего релиза (ADR-065), и переезд свёлся
 * к замене [MANIFEST_URL].
 *
 * **Скачанному не верим.** APK ставится только если он подписан **нашим** ключом
 * (отпечаток - ADR-047) и объявляет наше имя пакета. Без этой проверки любой, кто сумел
 * бы подменить ответ, поставил бы на телевизор что угодно - и Android принял бы это как
 * обновление браузера со всеми его закладками и cookies. Проверка идёт до установки,
 * по скачанному файлу, средствами платформы.
 */
internal object Updates {

    /**
     * Адрес описания последней сборки (ADR-065).
     *
     * `releases/latest/download/` - постоянная ссылка: GitHub сам перенаправляет её
     * на файл последнего выпуска, поэтому при каждом релизе адрес остаётся прежним,
     * а меняется только то, что по нему лежит. Редирект уводит на другой хост
     * (`objects.githubusercontent.com`), и [connect] идёт за ним сам: `HttpsURLConnection`
     * следует перенаправлениям внутри одного протокола, а здесь всюду https. Тот же путь
     * уже проверен живьём на скачивании APK - ссылка в описании ведёт туда же.
     *
     * **Старый склад-gist остаётся жить навсегда.** В него ходят все сборки по `0.1.15`
     * включительно: адрес зашит в них намертво, и выключенный gist означал бы, что эти
     * телевизоры больше никогда не увидят обновления. Поэтому при каждом выпуске
     * обновляются оба места.
     */
    private const val MANIFEST_URL =
        "https://github.com/therebecore/istok-browser/releases/latest/download/istok-update.json"

    /** Отпечаток нашего сертификата подписи, ADR-047. Строчными, без двоеточий. */
    private const val CERT_SHA256 =
        "3cde60d26e51fb58c5259e4cca280a05a9d5013048bb27526a98f8ac1bb85226"

    /**
     * Как часто спрашивать. Раз в сутки: чаще незачем (выпуски редкие), реже - человек
     * неделями сидел бы на старой сборке. Отсчёт от последней **удачной** проверки,
     * поэтому оборванная сеть не откладывает следующую попытку на сутки.
     */
    private const val CHECK_EVERY_MS = 24L * 60L * 60L * 1000L

    /** Описание - это несколько строк JSON. Всё, что больше, - не наше описание. */
    private const val MANIFEST_MAX = 4L * 1024L

    /** Потолок скачиваемого APK. Наш весит около 110 КБ, запас взят с большим избытком. */
    private const val APK_MAX = 8L * 1024L * 1024L

    private const val TIMEOUT_MS = 15_000

    private const val ACTION_STATUS = "io.github.therebecore.istok.INSTALL_STATUS"

    /** Сигнатурное разрешение из нашего манифеста, закрывает приёмник до Android 13 (ADR-062). */
    private const val STATUS_PERMISSION = "io.github.therebecore.istok.INSTALL_STATUS_RECEIVE"

    private val main = Handler(Looper.getMainLooper())

    /**
     * Приёмник текущей установки, если она идёт.
     *
     * Держится здесь ради одного случая: **отказа человека на экране установщика**.
     * Терминального ответа тогда не приходит вовсе, снимать приёмник нечему, и второе
     * нажатие «Обновить» заводило бы второй, третий, четвёртый - каждый со своим
     * сроком жизни до конца процесса (`B-167`). Одна установка - один приёмник.
     */
    private var status: BroadcastReceiver? = null

    /**
     * Посмотреть, нет ли новой версии, и позвать [onFound] на главном потоке, если есть.
     *
     * Молчит во всех остальных случаях - в том числе когда сети нет. Обновление это
     * не то, ради чего стоит показывать человеку ошибку: он о нём не просил.
     */
    fun check(context: Context, onFound: (UpdateInfo) -> Unit) {
        val app = context.applicationContext
        if (!Settings.isOn(app, Toggle.UPDATES)) return
        if (System.currentTimeMillis() - Settings.lastUpdateCheck(app) < CHECK_EVERY_MS) return
        checkNow(app) { found, _ -> if (found != null) onFound(found) }
    }

    /**
     * Проверить **сейчас**, по кнопке в настройках (ADR-055).
     *
     * Отличается от [check] ровно двумя вещами, и обе - следствие того, что человек нажал
     * кнопку сам: суточное окно не спрашивается, выключатель автопроверки тоже. Кнопка,
     * которая иногда молча ничего не делает, потому что «сегодня уже смотрели», хуже,
     * чем её отсутствие.
     *
     * [onResult] зовётся на главном потоке всегда, и в этом главное отличие от [check],
     * который при неудаче молчит: тому, кто нажал кнопку, обязаны ответить. `ok = false` -
     * описание не прочиталось (нет сети, раздача не ответила); `info = null` при `ok` -
     * установлена последняя версия.
     */
    fun checkNow(context: Context, onResult: (UpdateInfo?, Boolean) -> Unit) {
        val app = context.applicationContext
        Thread {
            val latest = fetchManifest()
            if (latest == null) {
                main.post { onResult(null, false) }
                return@Thread
            }
            // Отметка ставится за **удачно прочитанное описание**, а не за найденную
            // версию: иначе браузер, у которого и так последняя, ходил бы в сеть
            // на каждом запуске - то есть ровно тогда, когда обновляться нечему.
            Settings.setLastUpdateCheck(app, System.currentTimeMillis())
            val found = if (latest.versionCode > installedVersion(app)) latest else null
            main.post { onResult(found, true) }
        }.start()
    }

    /**
     * Скачать, проверить подпись и отдать системному установщику.
     *
     * [onState] зовётся на главном потоке идентификатором строки: человеку на телевизоре
     * нужно видеть, что что-то происходит, - канал у него может быть медленным.
     */
    fun install(context: Context, info: UpdateInfo, onState: (Int) -> Unit) {
        val app = context.applicationContext
        Thread {
            val file = File(app.cacheDir, "update.apk")
            try {
                main.post { onState(R.string.update_downloading) }
                download(info.url, file)
                main.post { onState(R.string.update_verifying) }
                val verdict = verify(app, file)
                if (verdict != Verdict.OK) {
                    file.delete()
                    val text = when (verdict) {
                        Verdict.BROKEN -> R.string.update_broken
                        Verdict.FOREIGN -> R.string.update_foreign
                        Verdict.OLD -> R.string.update_old
                        else -> R.string.update_wrong_signature
                    }
                    main.post { onState(text) }
                    return@Thread
                }
                main.post { onState(R.string.update_installing) }
                commit(app, file, onState)
            } catch (_: IOException) {
                main.post { onState(R.string.update_failed) }
            } catch (_: SecurityException) {
                main.post { onState(R.string.update_failed) }
            } finally {
                // Файл не нужен ни при каком исходе: установщик к этому моменту забрал
                // копию себе. Раньше он оставался в кэше после удачной установки -
                // мегабайт на диске телевизора без единого повода (`B-158`).
                file.delete()
            }
        }.start()
    }

    // --- Сеть -------------------------------------------------------------------------

    private fun fetchManifest(): UpdateInfo? {
        val text = try {
            connect(MANIFEST_URL).inputStream.use { it.readAtMost(MANIFEST_MAX) }
                .toString(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return null
        }
        val code = json.optInt("versionCode", 0)
        val name = shortName(json.opt("versionName") as? String ?: return null) ?: return null
        val url = json.opt("url") as? String ?: return null
        // Только https. Описание пришло по защищённому каналу, но проверить его
        // собственные поля всё равно обязаны мы: подменённое описание со ссылкой
        // на http увело бы скачивание в открытый канал.
        if (!url.startsWith("https://", ignoreCase = true)) return null
        if (code <= 0) return null
        return UpdateInfo(code, name, url)
    }

    /**
     * Имя версии из описания - строка из сети, и она идёт прямо человеку на экран:
     * в полосу обновления и в «О браузере». Четыре килобайта, разрешённые размером
     * описания, разъехались бы полосой во весь экран, а управляющие символы - ещё и
     * перекроили бы разметку (`B-165`). Отсюда потолок в 32 символа и отбрасывание
     * всего, что не печатается. Пустое имя - это не имя, и описание с ним не наше.
     */
    private fun shortName(raw: String): String? =
        raw.filter { it.code in 0x20..0x7E || it.code > 0xA0 }.take(32).trim().ifEmpty { null }

    /**
     * Скачивание с проверкой полноты: если сервер объявил длину, а пришло меньше - это
     * обрыв, и файл надо выбросить здесь. Иначе обрезанный APK доезжает до проверки
     * подписи и выглядит там подделкой, хотя виновата связь.
     */
    private fun download(url: String, into: File) {
        val connection = connect(url)
        val expected = connection.contentLength.toLong()
        connection.inputStream.use { stream ->
            into.outputStream().use { out ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > APK_MAX) throw IOException()
                    out.write(buffer, 0, read)
                }
                if (expected > 0 && total != expected) throw IOException()
            }
        }
    }

    /**
     * Соединение только по https и только с ответом 200.
     *
     * `HttpsURLConnection`, а не `HttpURLConnection`: с ним нельзя случайно оказаться
     * на http. Перенаправления платформа не переводит между протоколами сама, так что
     * увести нас с https переадресацией нельзя.
     */
    private fun connect(url: String): HttpsURLConnection {
        val connection = URL(fresh(url)).openConnection() as? HttpsURLConnection ?: throw IOException()
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        if (connection.responseCode != 200) {
            connection.disconnect()
            throw IOException()
        }
        return connection
    }

    /**
     * Тот же адрес, но с меткой времени в хвосте.
     *
     * Без неё обновление опаздывает на часы, и это не догадка: замерено 2026-08-19 -
     * через полминуты после выкладки новой сборки адрес всё ещё отдавал **предыдущее**
     * описание, потому что раздача держит ответ в кеше. Метка делает каждый запрос
     * новым для кеша, оставляя адрес прежним для нас. Касается и описания, и самого APK:
     * имя файла сборки не меняется от версии к версии, и по старому кешу приехала бы
     * ровно та версия, от которой мы обновляемся.
     */
    private fun fresh(url: String): String =
        url + (if (url.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis()

    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw IOException()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // --- Проверки ---------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun installedVersion(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }

    /**
     * Что именно не так со скачанным файлом. Раньше на все случаи было одно сообщение
     * «подписан не нами», и оно вводило в заблуждение: 2026-08-24 обновление на живом
     * телевизоре отказало именно так, а дело было не в подписи вовсе.
     */
    private enum class Verdict { OK, BROKEN, FOREIGN, WRONG_KEY, OLD }

    /**
     * Подписан ли скачанный файл нашим ключом и наше ли у него имя пакета.
     *
     * Разбирает **файл**, а не установленное приложение: это единственный момент, когда
     * можно отказаться. Подписей должно быть ровно одна - наша; несколько подписантов
     * у нашего APK не бывает, и принимать «в том числе нашу» нельзя.
     *
     * **Флаги запрашиваются оба, и берётся то, что платформа заполнила.** До 2026-08-24
     * здесь была развилка по версии: на Android 9 и новее спрашивался только
     * `GET_SIGNING_CERTIFICATES` и читался `signingInfo`. У **архива** это поле
     * заполняется не везде - на Яндекс Станции (YaOS поверх Android 9, сборка
     * PPR1.180610.011) оно приходило пустым, и каждое обновление отвергалось как
     * подделка. Проверить это было нечем: образ стенда - Android 8, там исполняется
     * вторая ветка. Отсюда правило: развилка по версии платформы, у которой проверяется
     * только одна сторона, - непроверенный код, и лучше не заводить её вовсе.
     *
     * `GET_SIGNATURES` объявлен устаревшим, но работает и на новых версиях, а подпись
     * схемы v2 платформа разбирает начиная с Android 7 (наш APK подписан только ею:
     * `minSdk` 26, и схема v1 при сборке не создаётся). Безопасность не страдает -
     * сверяется тот же отпечаток, а несколько подписантов отсекаются и здесь.
     */
    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File): Verdict {
        var flags = PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags = flags or PackageManager.GET_SIGNING_CERTIFICATES
        }
        val info = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: return Verdict.BROKEN
        if (info.packageName != context.packageName) return Verdict.FOREIGN
        // Номер версии до сих пор сверялся у **описания**, а файл принимался любой
        // (`B-164`). Подменённое описание могло назвать новую версию и привести старую
        // сборку с уже известной дырой - подписанную нами, то есть проходящую всё
        // остальное. От отката спасала бы и платформа, но отказ обязан случаться у нас.
        if (archiveVersion(info) <= installedVersion(context).toLong()) return Verdict.OLD
        val signatures: Array<Signature> = modernSigners(info)
            ?: info.signatures?.takeIf { it.isNotEmpty() }
            ?: return Verdict.BROKEN
        // Единственная подпись - это и проверка на нескольких подписантов: у нашего APK
        // подписант один, а «в том числе наша» подпись нам не годится.
        if (signatures.size != 1) return Verdict.WRONG_KEY
        if (fingerprint(signatures[0]) != CERT_SHA256) return Verdict.WRONG_KEY
        return Verdict.OK
    }

    @Suppress("DEPRECATION")
    private fun archiveVersion(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    /**
     * Подписи так, как их отдаёт Android 9 и новее. `null` означает «платформа этого
     * не заполнила» - и на старых версиях, и на тех, где `signingInfo` у архива пуст;
     * в обоих случаях ответ даёт прежнее поле [PackageInfo.signatures].
     */
    private fun modernSigners(info: PackageInfo): Array<Signature>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val signers = info.signingInfo?.apkContentsSigners
        return if (signers == null || signers.isEmpty()) null else signers
    }

    private fun fingerprint(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) out.append('0')
            out.append(Integer.toHexString(value))
        }
        return out.toString()
    }

    // --- Установка --------------------------------------------------------------------

    /**
     * Отдать файл системному установщику.
     *
     * Через `PackageInstaller`, а не через открытие файла: своего провайдера файлов
     * заводить не нужно, наружу ничего не экспортируется, а разрешение на установку
     * система спрашивает сама. Согласие человека при этом никуда не девается - установщик
     * показывает свой экран, и до его нажатия ничего не ставится.
     */
    private fun commit(context: Context, file: File, onState: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("istok", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            registerStatus(context, sessionId, onState)
            val intent = Intent(ACTION_STATUS).setPackage(context.packageName)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /**
     * Приёмник ответа установщика. Регистрируется кодом, а не в манифесте: он нужен
     * ровно на время одной установки.
     *
     * **Закрыт от чужих приложений на всех версиях, и это не украшение** (`B-161`,
     * ADR-062). До Android 13 у `registerReceiver` нет флага экспорта вовсе, и приёмник,
     * зарегистрированный без имени разрешения, доступен **любому** приложению на устройстве.
     * Внутри он берёт интент из широковещания и отдаёт его `startActivity` - то есть
     * чужое приложение могло открыть установщик **от нашего имени**, с уже выданным
     * человеком согласием «ставить из этого источника», которое он дал ради обновлений
     * браузера. Побочно тем же каналом подделывался отказ и запускалась любая чужая
     * Activity.
     *
     * Закрывается это сигнатурным разрешением, объявленным в нашем же манифесте:
     * широковещание примут только приложения, подписанные тем же ключом, то есть мы сами.
     * `ContextCompat.registerReceiver` внутри делает ровно это - библиотека ради одной
     * строки не нужна, ADR-009 цел.
     *
     * Вторая проверка, независимая от версии платформы: **номер сессии**. Даже свой
     * собственный ответ принимается только от той установки, которую мы начали.
     *
     * Подавление осталось, но причина у него теперь другая, и её стоит знать. Lint
     * распознаёт как защиту **только флаг** `RECEIVER_NOT_EXPORTED`; имя разрешения
     * в четырёхаргументной перегрузке он не видит и требует флаг, которого до Android 13
     * не существует. То есть правило срабатывает на коде, который как раз закрыт. Прежнее
     * обоснование `B-143` («снимается только добавлением androidx») было неверным по сути,
     * новое - «lint не умеет видеть разрешение», и проверять его заново не нужно:
     * достаточно убедиться, что четвёртый аргумент по-прежнему на месте.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerStatus(context: Context, sessionId: Int, onState: (Int) -> Unit) {
        status?.let { unregister(context, it) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Чужая или пустая сессия - не наш разговор.
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                // Умолчание не `-1`: оно **совпадает** со `STATUS_PENDING_USER_ACTION`,
                // и широковещание без единого поля читалось бы как «покажи установщик».
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                    // Установщик просит показать свой экран - без него система ничего
                    // не поставит, и это правильно: согласие даёт человек.
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = userAction(intent) ?: return
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                    }
                    // Успех означает, что нас сейчас перезапустят с новой версией,
                    // говорить об этом некому и незачем.
                    PackageInstaller.STATUS_SUCCESS -> unregister(context, this)
                    else -> {
                        unregister(context, this)
                        main.post { onState(R.string.update_failed) }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Имя разрешения вместо флага: до Android 13 это единственный способ
            // не выставить приёмник наружу. Разрешение сигнатурное и объявлено нами же,
            // поэтому отправителем можем быть только мы.
            context.registerReceiver(receiver, filter, STATUS_PERMISSION, null)
        }
        status = receiver
    }

    private fun unregister(context: Context, receiver: BroadcastReceiver) {
        if (status === receiver) status = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Уже снят - бывает, если ответов пришло больше одного.
        }
    }

    @Suppress("DEPRECATION")
    /**
     * Интент установщика из ответа.
     *
     * **Проверять здесь, что цель - системный установщик, оказалось нельзя** (`B-161`,
     * попытка 2026-08-25). Очевидный способ - `resolveActivity` плюс `getApplicationInfo`
     * и флаг `FLAG_SYSTEM` - требует `<queries>` в манифесте: с Android 11 видимость чужих
     * пакетов закрыта, и без объявления `resolveActivity` возвращает `null` **на живом
     * обновлении тоже**. То есть проверка, поставленная ради безопасности, сломала бы
     * саму установку - на lint это видно сразу (`QueryPermissionsNeeded`), на устройстве
     * проявилось бы молчаливым отказом обновляться.
     *
     * Канал закрыт двумя другими способами, и оба работают на всех версиях: сигнатурное
     * разрешение на приёмнике (ADR-062) и сверка номера сессии в [registerStatus].
     * Третья линия отложена - `B-171`.
     */
    private fun userAction(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
