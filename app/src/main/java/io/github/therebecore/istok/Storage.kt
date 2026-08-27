package io.github.therebecore.istok

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Сохранённый сайт: адрес и заголовок страницы в момент посещения.
 *
 * Обе строки - **чужие**: заголовок приходит со страницы, адрес может быть каким угодно.
 * Длина обеих ограничена при записи ([Storage.trimmed]): сайт, отдающий заголовок
 * в мегабайт, не должен раздувать наш файл, а на экране телевизора всё равно помещается
 * несколько десятков символов.
 */
internal class SiteEntry(val url: String, val title: String)

/**
 * Требование E-5: адрес, пришедший **с диска**, проверяется по схеме, а не только
 * экранируется. Сами мы ничего, кроме http и https, записать не можем - открыть другую
 * схему браузер и не даст (требование C-3), - но файлы на устройстве с доступом
 * к отладке подменяются, и тогда `javascript:alert(1)` доезжал до разметки наших
 * экранов: у такого адреса нет хоста, а подпись показывает **весь** адрес, когда хоста
 * нет. Проверка живёт здесь одна на всех: мест чтения с диска три - записи хранилища,
 * последний посещённый сайт, - и на Этапе 6 два из них её имели, а третье нет.
 */
internal fun isWebUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/**
 * Закладки, избранные и история на диске (ADR-025).
 *
 * Хранилище файловое, а не SQLite, и причина не в весе кода. Домашняя страница
 * показывается при запуске приложения, то есть чтение избранных попадает прямо в бюджет
 * времени старта, а история пишется на **каждой** навигации. Отсюда устройство:
 *
 * - избранные и закладки - обычные списки в JSON, читаются целиком;
 * - история - **лог с дописыванием в конец**, одна строка на запись: переход стоит
 *   одного открытия файла и одной записи, без чтения. Обрезается не по времени
 *   и не при старте, а тогда, когда файл перерос [HISTORY_MAX_BYTES], - в том же
 *   фоновом потоке, сразу после записи;
 * - последний посещённый сайт лежит в настройках рядом с выбором поисковика: полоса
 *   внизу домашней страницы нужна на старте, и открывать ради одной строки весь лог
 *   было бы тем самым, чего мы избегали, отказываясь от базы.
 *
 * Файл может оказаться испорченным - оборванной записью после смерти процесса или
 * подменой на устройстве с доступом к отладке. Требование E-2 в его нынешнем виде:
 * разбор **пропускает** битую запись и не падает на ней. Поэтому здесь нет ни одной
 * строки, собранной вручную, - только `org.json`, и ни одного разбора без `catch`.
 */
internal class Storage(context: Context) {

    private val app = context.applicationContext

    /** Внутреннее хранилище приложения: требование E-1. */
    private val dir: File = app.filesDir

    init {
        migrate()
    }

    /**
     * Данные с диска приведены к текущему формату (ADR-048).
     *
     * Обновление приложения поверх установленного сохраняет каталог с данными - Android
     * делает это сам. Потерять закладки и историю можно ровно двумя способами: сменить
     * ключ подписи (закрыто шагом 2, ADR-047) и **выпустить версию, которая не понимает
     * файлы предыдущей**. Второе и лечится отсюда.
     *
     * Метка одна на весь каталог, как `user_version` у SQLite, а не по номеру в каждом
     * файле: файлы пишутся и обновляются вместе, а три метки, которые могут разойтись
     * между собой, - это три источника правды вместо одного.
     *
     * **Правила на будущее, когда формат придётся менять:**
     * - поднять [FORMAT_VERSION] и дописать преобразование `from` -> следующая версия
     *   здесь же; преобразования выполняются подряд, поэтому каждое отвечает только
     *   за свой шаг;
     * - преобразование обязано переживать **отсутствие** файла: у человека может не быть
     *   ни одной закладки, а свежая установка приходит сюда с пустым каталогом;
     * - читатели ([entry]) и раньше пропускали запись с чужими полями и не падали
     *   на лишних - на это можно опираться, добавляя поле: старая запись без него
     *   останется читаемой, и преобразование ей не нужно.
     *
     * Откат на старую сборку (`from` больше текущего) не трогает ничего: превратить
     * данные обратно мы всё равно не умеем, а испортить метку - умеем.
     */
    private fun migrate() {
        val stamped = Settings.formatVersion(app)
        // Метки нет (`0`) - это данные, записанные до Этапа 9, и их формат первый:
        // отдельного имени для этого не нужно, ноль меньше любого формата (`B-157`).
        if (stamped > FORMAT_VERSION) return
        // Преобразований пока нет: формат первый и единственный. Место для них - здесь,
        // до записи новой метки, иначе поломка посреди преобразования пометит данные
        // как приведённые.
        if (stamped != FORMAT_VERSION) Settings.setFormatVersion(app, FORMAT_VERSION)
    }

    /**
     * Запись идёт с одного фонового потока - и то, и другое важно. С главного потока
     * ей нельзя: она попадает на навигацию, то есть на момент, когда пользователь ждёт
     * страницу. Потоков не может быть несколько: порядок записей в логе истории - это
     * и есть хронология, а параллельная запись её перемешает.
     */
    private val thread = HandlerThread("istok-storage").also { it.start() }
    private val worker = Handler(thread.looper)

    // --- Избранные и закладки -------------------------------------------------------

    /**
     * Списки в памяти - и это не ускорение, а починка (`B-30`, ревью Этапа 6).
     *
     * Запись уходит на фоновый поток, а спрашивают список сразу же, на следующей строке:
     * файл в этот момент ещё старый, и экран собирался по нему - удалённая плитка
     * оставалась на месте до следующей перерисовки. Обходили это тем, что вызывающий код
     * протаскивал свою копию списка через пять параметров подряд, до самой сборки
     * разметки. Теперь копия одна и живёт здесь: тот, кто пишет, тот и обновляет.
     *
     * `null` - файл ещё не читали. Для закладок это важно отдельно: на старте открывается
     * домашняя страница, и `bookmarks.json` до первого сайта не трогается вовсе
     * (ADR-025, наблюдение `B-25`).
     */
    private var favoritesCache: List<SiteEntry>? = null
    private var bookmarksCache: List<SiteEntry>? = null
    private var pinnedCache: List<SiteEntry>? = null

    /**
     * Избранные для домашней страницы. Первое чтение синхронное и попадает на старт:
     * рисовать до них всё равно нечего, а файл меньше килобайта.
     *
     * Лишнее сверх [MAX_FAVORITES] отбрасывается при чтении, а не только при записи -
     * файл на диске мог быть подменён, а раскладка плиток рассчитана на шесть.
     */
    fun favorites(): List<SiteEntry> = favoritesCache
        ?: read(File(dir, FILE_FAVORITES), MAX_FAVORITES).also { favoritesCache = it }

    fun saveFavorites(items: List<SiteEntry>) {
        // Копия обязательна: наружу отдаётся `MutableList`, который вызывающий код
        // правит дальше - без копии кэш менялся бы у нас за спиной.
        favoritesCache = items.toList()
        write(File(dir, FILE_FAVORITES), items)
    }

    /** Обычные закладки. Читаются при открытии своего экрана, не на старте. */
    fun bookmarks(): List<SiteEntry> = bookmarksCache
        ?: read(File(dir, FILE_BOOKMARKS), MAX_BOOKMARKS).also { bookmarksCache = it }

    fun saveBookmarks(items: List<SiteEntry>) {
        bookmarksCache = items.toList()
        write(File(dir, FILE_BOOKMARKS), items)
    }

    /**
     * Закреплённые записи истории (ADR-054). Своя сущность и свой файл: до этого
     * закрепление складывало запись в закладки, и получалось, что закрепить страницу
     * нельзя, не заведя на неё закладку.
     *
     * Иммунитет к очистке истории у закреплённых остался: хранилище отдельное, и
     * [clearHistory] его не трогает.
     */
    fun pinned(): List<SiteEntry> = pinnedCache
        ?: read(File(dir, FILE_PINNED), MAX_PINNED).also { pinnedCache = it }

    fun savePinned(items: List<SiteEntry>) {
        pinnedCache = items.toList()
        write(File(dir, FILE_PINNED), items)
    }

    // --- История --------------------------------------------------------------------

    /**
     * Переход записан. Вызывается на каждой навигации, поэтому делает ровно две вещи:
     * дописывает строку в лог и запоминает последний сайт в настройках.
     */
    fun recordVisit(entry: SiteEntry) {
        val url = trimmed(entry.url, MAX_URL)
        val title = trimmed(entry.title, MAX_TITLE)
        val line = try {
            JSONObject().put(KEY_URL, url).put(KEY_TITLE, title).toString() + "\n"
        } catch (_: JSONException) {
            return
        }
        historyCleared = false
        // Адрес открыли снова - значит он снова в истории, даже если строку удаляли:
        // без этого удалённая когда-то запись не показалась бы после нового посещения.
        removed.remove(url)
        // Обрезка нужна здесь ровно так же, как в логе, и по причине более серьёзной:
        // настройки читаются **на старте**. Пока сюда уходили исходные строки, страница
        // с адресом в мегабайт (`history.pushState` его позволяет) давала `settings.xml`
        // в 1 004 266 Б - измерено аудитом Этапа 6.
        Settings.setLastVisit(app, url, title)
        worker.post {
            val log = File(dir, FILE_HISTORY)
            try {
                log.appendText(line)
            } catch (_: IOException) {
                return@post
            }
            if (log.length() > HISTORY_MAX_BYTES) trimHistory(log)
        }
    }

    /**
     * История для своего экрана: **свежие первыми**, повторные посещения одного адреса
     * схлопнуты в последнее. Читается только здесь - на старте лог не открывается вовсе.
     */
    fun history(): List<SiteEntry> {
        // Отвечаем по флагу, не заглядывая на диск: удаление стоит в очереди потока
        // хранилища и к этому мгновению могло ещё не выполниться, а человеку уже сказано,
        // что история стёрта. Порядок относительно записей гарантирует сама очередь
        // (`B-72`), флаг же убирает задержку на нашем собственном экране.
        if (historyCleared) return emptyList()
        val lines = try {
            File(dir, FILE_HISTORY).readLines()
        } catch (_: IOException) {
            return emptyList()
        }
        val seen = HashSet<String>()
        val result = ArrayList<SiteEntry>()
        for (i in lines.indices.reversed()) {
            val entry = parse(lines[i]) ?: continue
            // Удалённое по кнопке пропускаем, не дожидаясь диска, - по той же причине,
            // по которой стоит флаг очистки: переписывание лога идёт в очереди.
            if (entry.url in removed) continue
            if (!seen.add(entry.url)) continue
            result.add(entry)
            if (result.size >= HISTORY_MAX_ENTRIES) break
        }
        return result
    }

    /**
     * Историю вычистили.
     *
     * Удаление идёт **в очередь потока хранилища**, а не на вызывающем потоке, и в этом
     * весь смысл (`B-72`): запись предыдущей навигации уже стоит в той же очереди, и
     * удаление, выполненное здесь и сейчас, она бы пережила - файл появился бы заново
     * через мгновение после того, как человеку сказали «история стёрта». В очереди же
     * порядок обязателен: сначала допишется всё, что было, потом файла не станет.
     *
     * Ждём результат прямо здесь, потому что очистка данных последним шагом снимает
     * процесс (ADR-036): отложенная работа до диска попросту не доедет. Ожидание с
     * пределом - главный поток нельзя оставлять запертым навсегда из-за занятой очереди;
     * не дождались - файл переживёт очистку, но это лучше зависшего браузера.
     *
     * Заодно сносится `.tmp` от [writeAtomic]: если процесс сняли посреди [trimHistory],
     * на диске остаётся временный файл с адресами, и очистка обязана убрать и его.
     */
    fun clearHistory() {
        historyCleared = true
        removed.clear()
        Settings.clearLastVisit(app)
        val done = CountDownLatch(1)
        worker.post {
            File(dir, FILE_HISTORY).delete()
            File(dir, FILE_HISTORY + SUFFIX_TMP).delete()
            done.countDown()
        }
        done.await(CLEAR_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Лог вычистили; отложенная запись, если она была в очереди, к нему уже не относится.
     *
     * Флаг оставлен и после `B-72`, хотя очередь теперь гарантирует порядок сама: он
     * отвечает экрану истории **в этом процессе** мгновенно, не дожидаясь диска.
     */
    private var historyCleared = false

    /**
     * Одну запись истории удалили (ADR-054). Подтверждения нет - решение пользователя
     * 2026-08-22: нажал корзину, строка исчезла.
     *
     * Строка списка - это адрес, а не отдельное посещение (повторные схлопнуты в
     * [history]), поэтому из лога уходят **все** его записи. Удаление настоящее, а не
     * пометка: браузер, который прячет адрес на экране, но держит его на диске, врёт
     * человеку, который эту историю чистит.
     *
     * Переписывание лога идёт в очереди потока хранилища - как и очистка, и по той же
     * причине (`B-72`): запись предыдущей навигации может стоять в ней перед нами.
     * Экрану же отвечает [removed], иначе список пересобрался бы по ещё не переписанному
     * файлу и удалённая строка осталась бы на месте до следующего открытия (`B-30`).
     */
    fun removeVisit(url: String) {
        removed.add(url)
        if (Settings.lastVisit(app)?.url == url) Settings.clearLastVisit(app)
        worker.post {
            val log = File(dir, FILE_HISTORY)
            val lines = try {
                log.readLines()
            } catch (_: IOException) {
                return@post
            }
            val kept = lines.filter { parse(it)?.url != url }
            if (kept.size == lines.size) return@post
            if (kept.isEmpty()) {
                log.delete()
            } else {
                writeAtomic(log, kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /**
     * Адреса, удалённые из истории в этом запуске. Живёт только до переписывания лога,
     * то есть доли секунды, - но экран за это время успевает пересобраться.
     *
     * Трогается **только с главного потока**: и удаление, и чтение истории приходят
     * с экрана. Фоновому потоку множество не нужно, ему уходит сам адрес.
     */
    private val removed = HashSet<String>()

    /**
     * Лог перерос порог - оставляем свежий хвост. Ограничения два, и держать надо оба.
     *
     * Раньше здесь первой строкой стоял выход по числу записей, и он отменял второе
     * условие целиком: при адресах до [MAX_URL] файл переваливает [HISTORY_MAX_BYTES]
     * на шестом десятке записей, до трёхсот строк дело не доходит никогда - лог рос
     * без предела, а каждая навигация читала его целиком и не обрезала ничего
     * (замер `.bench\pg6-histtrim.ps1`, прогон П: 60 записей - 147 522 Б).
     *
     * Обрезка идёт по строкам, а не по байтам: обрезанная посередине строка стала бы
     * битой записью, и хотя разбор её переживёт, плодить мусор, который мы же и создали,
     * незачем. Хвост берётся с запасом к порогу ([HISTORY_TRIM_BYTES]): обрезка ровно
     * до границы оставила бы файл у самой черты, и следующая же запись снова читала бы
     * его целиком.
     */
    private fun trimHistory(log: File) {
        val lines = try {
            log.readLines()
        } catch (_: IOException) {
            return
        }
        var from = maxOf(0, lines.size - HISTORY_MAX_ENTRIES)
        var bytes = 0L
        for (i in lines.size - 1 downTo from) {
            bytes += lines[i].toByteArray().size + 1
            if (bytes > HISTORY_TRIM_BYTES) {
                from = i + 1
                break
            }
        }
        if (from == 0) return
        val tail = lines.subList(from, lines.size)
        writeAtomic(log, tail.joinToString("\n", postfix = "\n"))
    }

    /**
     * Приложение уходит с экрана насовсем. Поток гасится явно: у браузера не должно
     * оставаться работающих задач после ухода, это же правило действует для курсора
     * и для движка (ADR-019). `quitSafely` даёт дописать уже принятые записи.
     */
    fun close() {
        thread.quitSafely()
    }

    // --- Файлы ----------------------------------------------------------------------

    private fun read(file: File, limit: Int): List<SiteEntry> {
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return emptyList()
        }
        val array = try {
            JSONArray(text)
        } catch (_: JSONException) {
            return emptyList()
        }
        val result = ArrayList<SiteEntry>(minOf(array.length(), limit))
        for (i in 0 until array.length()) {
            if (result.size >= limit) break
            val item = array.optJSONObject(i) ?: continue
            result.add(entry(item) ?: continue)
        }
        return result
    }

    /**
     * Разметка собирается **на вызывающем потоке**, а на фоновый уходит готовая строка.
     * Иначе фоновый поток разбирал бы список, который вызывающий код в это же время
     * правит: и избранные, и закладки меняются прямо с экрана.
     */
    private fun write(file: File, items: List<SiteEntry>) {
        val array = JSONArray()
        try {
            for (entry in items) {
                array.put(
                    JSONObject()
                        .put(KEY_URL, trimmed(entry.url, MAX_URL))
                        .put(KEY_TITLE, trimmed(entry.title, MAX_TITLE))
                )
            }
        } catch (_: JSONException) {
            return
        }
        val text = array.toString()
        worker.post { writeAtomic(file, text) }
    }

    /**
     * Запись через временный файл с переименованием: прерванная на середине запись
     * не оставляет обрезанного списка избранных вместо целого.
     */
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.path + SUFFIX_TMP)
        try {
            tmp.writeText(text)
        } catch (_: IOException) {
            tmp.delete()
            return
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    private fun parse(line: String): SiteEntry? {
        if (line.isEmpty()) return null
        val item = try {
            JSONObject(line)
        } catch (_: JSONException) {
            return null
        }
        return entry(item)
    }

    /**
     * Запись файла - в сущность, или ничего. Поля берутся **строго строками**:
     * `optString` приводит к строке что угодно, и подменённый файл с `{"u":123,"t":null}`
     * давал плитку с заголовком «null» и адресом «123» вместо того, чтобы отброситься
     * целиком, как отбрасываются `{}` и `[]`. Требование E-2: битую запись пропускаем.
     */
    private fun entry(item: JSONObject): SiteEntry? {
        val url = item.opt(KEY_URL) as? String ?: return null
        if (!isWebUrl(url)) return null
        val title = item.opt(KEY_TITLE) as? String ?: return null
        return SiteEntry(url, title)
    }

    private fun trimmed(value: String, max: Int): String =
        if (value.length <= max) value else value.substring(0, max)

    companion object {
        /**
         * Версия формата данных на диске (ADR-048). Поднимается тогда и только тогда,
         * когда новая сборка не может прочитать файлы предыдущей как есть; вместе
         * с номером в [migrate] дописывается преобразование.
         */
        private const val FORMAT_VERSION = 1

        /** Больше шести плиток не помещается в раскладку домашней страницы. */
        const val MAX_FAVORITES = 6

        /**
         * Потолок закладок. Он не про место на диске, а про экран: список, по которому
         * ходят курсором с пульта, длиннее пары сотен строк бесполезен, а недоверенные
         * данные обязаны иметь предел роста.
         */
        // Не приватный: закрепление записи истории (B-88) обязано знать тот же предел,
        // иначе добавленное сверх него молча пропадало бы при следующем чтении файла.
        const val MAX_BOOKMARKS = 200

        /**
         * Потолок закреплённых (ADR-054). Тот же, что у закладок, и по той же причине:
         * это список на экране, по которому ходят курсором.
         */
        const val MAX_PINNED = 200

        /** Жёсткий лимит истории, решение по Этапу 6. */
        private const val HISTORY_MAX_ENTRIES = 300

        /**
         * Порог обрезки лога. Взят с запасом к [HISTORY_MAX_ENTRIES]: адреса бывают
         * длинными, и обрезка должна срабатывать по факту роста файла, а не по расчётной
         * средней длине записи. Что именно из двух ограничений сработает первым, зависит
         * от длины адресов: на коротких упрёмся в число записей, на длинных - в размер.
         */
        private const val HISTORY_MAX_BYTES = 128L * 1024L

        /**
         * До какого размера обрезаем. Меньше порога намеренно: обрезка ровно до
         * [HISTORY_MAX_BYTES] оставила бы файл у самой границы и запускалась бы снова
         * на каждой следующей записи.
         */
        private const val HISTORY_TRIM_BYTES = 96L * 1024L

        private const val MAX_URL = 2000
        private const val MAX_TITLE = 200

        private const val FILE_FAVORITES = "favorites.json"
        private const val FILE_BOOKMARKS = "bookmarks.json"
        private const val FILE_PINNED = "pinned.json"
        private const val FILE_HISTORY = "history.jsonl"

        /** Хвост временного файла [writeAtomic]. Очистка обязана сносить и его (`B-72`). */
        private const val SUFFIX_TMP = ".tmp"

        /**
         * Сколько [clearHistory] ждёт поток хранилища. Очередь к этому моменту почти
         * всегда пуста - там одна запись на навигацию, - но главный поток нельзя
         * запирать без предела: секунды хватит и на занятую очередь, и на медленный диск.
         */
        private const val CLEAR_WAIT_MS = 1000L

        private const val KEY_URL = "u"
        private const val KEY_TITLE = "t"
    }
}
