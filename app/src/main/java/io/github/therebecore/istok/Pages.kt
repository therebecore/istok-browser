package io.github.therebecore.istok

import android.net.Uri
import android.text.TextUtils.htmlEncode
import java.util.Locale

/**
 * Схема, по которой локальные страницы разговаривают с приложением. Единственный
 * способ связи: JavaScript-мостов нет и не будет (ADR-009), а ссылку с этой схемой
 * ловит `BrowserWebViewClient.shouldOverrideUrlLoading`.
 */
internal const val INTERNAL_SCHEME = "istok"

/** Открыть избранное по номеру или последний сайт по [LAST_ID]. */
internal const val CMD_OPEN = "open"

internal const val LAST_ID = "last"

/**
 * Команды, **меняющие состояние** (требование H-8): пометить закладку избранной, убрать
 * запись, переставить плитку. Каждая идёт с одноразовым ключом, который приложение
 * кладёт в разметку в момент сборки страницы и проверяет при переходе.
 *
 * Ключ здесь не формальность. Ссылка `istok://` работает с **любой** страницы: чужой
 * сайт нарисует кнопку «Смотреть», а под ней будет удаление избранного, и нажмёт её
 * сам пользователь. Переход (`open`) ключа не требует - увести человека чужая страница
 * может и обычной ссылкой, - а удаление и перестановка требуют.
 */
internal const val CMD_FAVORITE = "fav"
internal const val CMD_REMOVE = "del"

/**
 * Закрепить запись истории (B-87 списка пользователя, шаг 9б). Отдельной команды
 * заслуживает потому, что номер приходит **из истории**, а кладётся запись в
 * закреплённые: [CMD_FAVORITE] и [CMD_REMOVE] работают с номерами в том списке,
 * который на экране.
 */
internal const val CMD_PIN = "pin"

/**
 * Сделать закладкой запись истории или закреплённую (ADR-054). Отдельная команда
 * по той же причине, что и [CMD_PIN]: номер приходит из одного списка, а запись
 * уезжает в другой.
 */
internal const val CMD_MARK = "mark"
internal const val CMD_MOVE = "move"
internal const val CMD_CLEAR = "clear"

/** Открыть экран закладок, истории, настроек или «о браузере»: переход, ключа не требует. */
internal const val CMD_BOOKMARKS = "bookmarks"
internal const val CMD_HISTORY = "history"
internal const val CMD_SETTINGS = "settings"
internal const val CMD_ABOUT = "about"

/**
 * Экран поддержки (ADR-060). Тоже переход и тоже без ключа, но с оговоркой: он
 * показывает адреса кошельков, то есть место, где подмена стоила бы человеку денег.
 * Защита здесь не в ключе - адреса зашиты в сборку и подставить свой чужая страница
 * не может, - а в том, что команда принимается только с наших экранов
 * (`OWN_SCREEN_ONLY`): иначе сайт мог бы открыть настоящий экран поддержки в момент,
 * когда человек ждёт совсем другого.
 */
internal const val CMD_DONATE = "donate"

/**
 * Переключить настройку. Ключ обязателен: выключить проверку опасных сайтов чужой
 * ссылкой - ровно тот случай, ради которого требование H-8 и заведено. Аргумент -
 * ключ настройки строкой ([Toggle.key]), а не номер строки на экране: номер зависел бы
 * от порядка перечисления, то есть от вида экрана.
 */
internal const val CMD_SET = "set"

/**
 * Сменить язык интерфейса (ADR-058). Ключ обязателен: команда меняет состояние
 * и перезапускает Activity, а подсунуть человеку экран на языке, которого он не знает,
 * чужая страница не должна. Аргумент - метка языка ([Language.tag]), пустая означает
 * «как в системе».
 */
internal const val CMD_LANG = "lang"

/**
 * Проверить обновления прямо сейчас (ADR-055). Ключ обязателен, хотя команда ничего
 * не меняет на устройстве: она **выводит браузер в сеть**, а это то единственное, что
 * он делает не по просьбе человека, и решать за него, когда сходить наружу, чужая
 * страница не должна.
 */
internal const val CMD_CHECK_UPDATE = "update-check"

/** Спросить перед чисткой истории. Показ вопроса ничего не меняет - ключа не требует. */
internal const val CMD_CLEAR_ASK = "clear-ask"

/**
 * Очистка данных браузера целиком (требование E-3): история, cookies, кэш, хранилища
 * страниц, данные форм. [CMD_WIPE_ASK] показывает вопрос и ключа не требует, сама
 * очистка [CMD_WIPE] - требует, как и любая необратимая команда.
 *
 * Отдельно от [CMD_CLEAR] намеренно: та чистит **только лог истории** и живёт на своём
 * экране, у корзины. Свести их в одну команду с аргументом значило бы, что промах
 * в аргументе стирает больше, чем человек просил.
 */
internal const val CMD_WIPE_ASK = "wipe-ask"
internal const val CMD_WIPE = "wipe"

/**
 * Открыть поле переименования. Ключа не требует **намеренно**: само по себе оно ничего
 * не меняет, название сохраняет уже клавиша «ввод» нашего собственного поля, куда чужая
 * страница не дотянется никак. С ключом здесь был дефект: карандаш сжигал ключ, а
 * страница при этом не перерисовывалась, и второе нажатие подряд молча не работало
 * (найдено пользователем). Чужую страницу отсекает не ключ, а проверка того, что
 * на экране сейчас наш список - см. `onPageCommand`.
 */
internal const val CMD_RENAME = "rename"

/** Включить и выключить режим правки избранного. Вид, а не данные - ключа не требует. */
internal const val CMD_EDIT = "edit"

internal const val MOVE_LEFT = "l"
internal const val MOVE_RIGHT = "r"

/**
 * Локальные страницы браузера: заглушка старта, экраны ошибок, запрос подтверждения.
 *
 * Все они грузятся через `loadDataWithBaseURL` с базовым URL `null`, то есть получают
 * пустое происхождение и не наследуют прав ни одного сайта. Связь с приложением -
 * только через перехват собственной схемы в `shouldOverrideUrlLoading`, никаких
 * JavaScript-мостов (ADR-009). JavaScript на этих страницах не используется вовсе.
 *
 * Всё, что подставляется в разметку, приходит с чужого сайта - адрес, имя хоста,
 * системное описание ошибки. Каждая такая строка проходит через `htmlEncode`
 * (требование E-4): без этого адрес вида `http://x/?a=<script>...` выполнил бы
 * скрипт на нашей же локальной странице.
 *
 * Вёрстка под просмотр с трёх метров: крупный кегль, высокий контраст, широкие поля
 * под overscan телевизора.
 */

private const val PAGE_STYLE = """
  html, body { height: 100%; margin: 0; }
  body {
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    background: #0B0E11; color: #E6EAED;
    font-family: sans-serif; text-align: center;
    padding: 0 8%; box-sizing: border-box;
  }
  h1 { font-size: 44px; font-weight: 700; margin: 0 0 20px; line-height: 1.2; }
  p { font-size: 24px; color: #B4BCC4; margin: 0; line-height: 1.45; max-width: 900px; }
  .host {
    font-size: 34px; font-weight: 700; color: #E6EAED; margin-top: 32px;
    word-break: break-all; max-width: 900px;
  }
  .detail {
    font-size: 18px; color: #6B757E; margin-top: 28px;
    word-break: break-all; max-width: 900px;
  }
  .warn { color: #E3B341; }
  .bad { color: #E5534B; }
  a.act {
    display: inline-block; margin-top: 40px; padding: 16px 40px;
    font-size: 22px; color: #0B0E11; background: #E6EAED;
    border-radius: 6px; text-decoration: none; border: 3px solid transparent;
  }
  a.act:focus { border-color: #6BA6F0; outline: none; }
  /* Курсор с пульта даёт наведение, а не фокус: без этого правила при двух кнопках
     рядом не видно, в какую целишься. */
  a.act:hover { border-color: #6BA6F0; }
  a.act + a.act { margin-left: 24px; }
  a.act.danger { background: #E5534B; color: #FFFFFF; }
"""

/**
 * Язык локальных страниц. Текст на них приходит из ресурсов, то есть уже на языке
 * системы, и `lang` обязан говорить о нём правду: по нему движок подбирает шрифт
 * и переносы, а озвучка - произношение. Код языка приходит от платформы и состоит
 * из букв, подстановка в разметку безопасна.
 */
private fun pageLang(): String = Locale.getDefault().language.ifEmpty { "en" }

/**
 * CSP здесь - страховка на будущее, а не защита от чего-то сегодняшнего: скриптов
 * на этих страницах нет и не планируется. Но на Этапе 6 в разметку пойдут заголовки
 * и адреса из истории, то есть строки с чужих сайтов, и запрет всего, кроме собственных
 * стилей, обесценит целый класс ошибок подстановки заранее.
 */
private fun page(body: String, style: String = PAGE_STYLE): String =
    "<!doctype html><html lang=\"" + pageLang() + "\"><head>" +
        "<meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" " +
        "content=\"default-src 'none'; style-src 'unsafe-inline'\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>" + style + "</style></head><body>" + body + "</body></html>"

/**
 * Стиль домашней страницы. Собран по утверждённому макету `design/home-mockup.html`,
 * но нарочно консервативен: ни `gap`, ни `aspect-ratio`, ни сеточной раскладки.
 *
 * Причина в том же, из-за чего в браузере есть проверка версии движка: System WebView
 * на приставке бывает сколь угодно старым, а перечисленное появилось в Chromium 84-88.
 * На таком движке `gap` просто игнорируется - плитки слипаются, - а `aspect-ratio`
 * схлопывает их в полоски. Отступы полями и высота через `calc` работают везде,
 * куда мы вообще способны попасть.
 *
 * Единицы - от размера окна (`vw`/`vh`), потому что домашняя страница всегда занимает
 * его целиком, а расстояние до телевизора не даёт полагаться на кегль по умолчанию.
 */
private const val HOME_STYLE = """
  html, body { height: 100%; margin: 0; }
  body {
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    background: #0E1216; color: #E8EEF2;
    font-family: sans-serif; overflow: hidden;
  }
  .tiles { text-align: center; }
  .row { white-space: nowrap; margin-bottom: 2.4vh; }
  /*
    Скругления живут по двум числам и только по ним: 12px крупным блокам - плиткам,
    полосе последнего сайта, строкам списков, - и 6px мелким: значкам, кнопкам правки,
    корзине. Шесть выбраны не на глаз, а по клавиатуре и кнопкам диалогов: они стоят
    на 6dp с Этапа 5, и до 2026-08-29 страницы с их 2px выглядели рядом чужими.
  */
  .tile {
    display: inline-block; vertical-align: top;
    width: var(--tw); height: calc(var(--tw) / 2);
    margin: 0 1.2vw;
    background: #171E24; border: 1px solid #2A353E; border-radius: 12px;
    text-decoration: none; color: #E8EEF2;
    box-sizing: border-box; padding-top: 3.4vh;
    text-align: center; overflow: hidden;
  }
  .one { --tw: 27vw; }
  .two { --tw: 24vw; }
  .solo { --tw: 34vw; }
  .mark {
    display: block; width: 8vh; height: 8vh; line-height: 8vh;
    margin: 0 auto 1.6vh; border-radius: 6px;
    font-size: 4.6vh; font-weight: 700; color: #0E1216;
  }
  .name {
    display: block; font-size: 3.9vh; padding: 0 8%;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  /* Плюс живёт один и центрируется по всей плитке: отступ сверху рассчитан на значок
     с подписью под ним, и без подписи он поднимал плюс над серединой. */
  .add {
    display: inline-flex; align-items: center; justify-content: center;
    padding-top: 0;
    background: transparent; border-style: dashed; border-color: #35434E;
  }
  .add .plus {
    display: block; font-size: 8vh; line-height: 1; color: #4B5D6A; font-weight: 300;
  }
  /*
    В режиме правки плитка выше обычной: кнопки идут под названием, а высота плитки
    задана пропорцией 2:1 и режет всё, что не поместилось. Поэтому здесь высота
    считается по содержимому, а пропорция остаётся минимумом.
  */
  .tile.edit {
    height: auto; min-height: calc(var(--tw) / 2);
    padding-top: 2.4vh; padding-bottom: 2vh;
  }
  .edit .tools { display: block; margin-top: 1.4vh; }
  /* Флексом, а не высотой строки: значок карандаша нарисован разметкой, и по базовой
     линии, как знак «✕», он не встанет. */
  .tool {
    display: inline-flex; align-items: center; justify-content: center;
    width: 6.4vh; height: 6.4vh; vertical-align: middle;
    margin: 0 0.5vw; border: 1px solid #2A353E; border-radius: 6px;
    background: #0E1216; color: #9FB0BC; font-size: 3.4vh; text-decoration: none;
  }
  .tool svg { width: 3.4vh; height: 3.4vh; }
  .tool:hover { border-color: #6BA6F0; color: #E8EEF2; background: #2C4A73; }
  .drop:hover { border-color: #E5534B; color: #E5534B; }
  .edit { border-color: #3D4C57; }
  .editbtn {
    display: inline-block; margin-top: 1.6vh; padding: 1vh 2.4vw;
    border: 1px solid #2A353E; border-radius: 6px;
    color: #7D8F9B; font-size: 2.9vh; text-decoration: none;
  }
  .editbtn:hover { border-color: #6BA6F0; color: #E8EEF2; background: #2C4A73; }
  .editbtn.on { border-color: #6BA6F0; color: #6BA6F0; }
  .tile:hover { border-color: #6BA6F0; background: #2C4A73; }
  .last { width: 88%; margin-top: 2vh; border-top: 1px solid #2A353E; padding-top: 2.4vh; }
  .strip {
    display: block; height: 13vh; box-sizing: border-box;
    background: #171E24; border: 1px solid #2A353E; border-radius: 12px;
    padding: 0 2.6vw; text-decoration: none; color: #E8EEF2;
    white-space: nowrap; overflow: hidden;
  }
  .strip:hover { border-color: #6BA6F0; background: #2C4A73; }
  .strip:hover .t2, .strip:hover .hint { color: #C6D8EC; }
  .strip .mark {
    display: inline-block; width: 6.4vh; height: 6.4vh; line-height: 6.4vh;
    margin: 3.3vh 2vw 0 0; vertical-align: top; font-size: 3.6vh;
  }
  .strip .txt {
    display: inline-block; vertical-align: top; margin-top: 2.6vh;
    max-width: 60%; overflow: hidden;
  }
  .strip .t1 {
    display: block; font-size: 4vh;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .strip .t2 {
    display: block; font-size: 3.1vh; color: #7D8F9B;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .strip .hint { float: right; font-size: 3.1vh; color: #7D8F9B; line-height: 13vh; }
  .warnbar { font-size: 2.6vh; color: #E3B341; margin-bottom: 2vh; padding: 0 6vw; text-align: center; }
  .c0 { background: #E8544A } .c1 { background: #4FB6A5 } .c2 { background: #9146FF }
  .c3 { background: #E3B341 } .c4 { background: #4A8FE8 } .c5 { background: #E86AA8 }
  .c6 { background: #6FBF4A } .c7 { background: #E8874A }
"""

/**
 * Экраны списков - закладки и история. Вёрстка нарочно скупая и та же, что у полосы
 * последнего сайта на домашней: строка со значком, названием и хостом. Список
 * прокручивается краем экрана, как обычная страница, поэтому своей прокрутки
 * и своих клавиш ему не нужно.
 */
private const val LIST_STYLE = """
  html, body { height: 100%; margin: 0; }
  body {
    background: #0E1216; color: #E8EEF2;
    font-family: sans-serif; padding: 4vh 6vw; box-sizing: border-box;
  }
  .listhead {
    display: flex; align-items: center; justify-content: space-between;
    font-size: 4.4vh; font-weight: 700; margin-bottom: 3vh;
  }
  /*
    Корзина стоит у заголовка списка, а не в меню: чистят историю там же, где на неё
    смотрят. Значок нарисован в разметке, а не буквой шрифта: на старом движке
    телевизора символ корзины из Unicode рискует оказаться пустым квадратом.
  */
  .trash {
    display: flex; align-items: center; justify-content: center;
    width: 9vh; height: 9vh; flex: none;
    border: 1px solid #2A353E; border-radius: 6px;
    color: #7D8F9B; text-decoration: none;
  }
  .trash svg { width: 4.4vh; height: 4.4vh; }
  .trash:hover { border-color: #E5534B; color: #E5534B; background: #1E272E; }
  .empty { font-size: 3.4vh; color: #7D8F9B; }
  .row {
    display: block; height: 11vh; box-sizing: border-box; margin-bottom: 1.4vh;
    background: #171E24; border: 1px solid #2A353E; border-radius: 12px;
    padding: 0 2.2vw; text-decoration: none; color: #E8EEF2;
    white-space: nowrap; overflow: hidden;
  }
  .row:hover { border-color: #6BA6F0; background: #2C4A73; }
  .row:hover .t2 { color: #C6D8EC; }
  .line { display: flex; align-items: stretch; margin-bottom: 1.4vh; }
  .line .row { flex: 1; margin-bottom: 0; height: auto; }
  /*
    Кнопки тянутся по высоте строки, а знак внутри центрируется флексом.
    Фиксированный line-height здесь врал: у строки своя высота, и значки
    оказывались выше её середины.
  */
  .act {
    display: flex; align-items: center; justify-content: center;
    width: 11vh; margin-left: 1.2vh; box-sizing: border-box;
    background: #171E24; border: 1px solid #2A353E; border-radius: 6px;
    color: #7D8F9B; font-size: 4.4vh; text-decoration: none;
  }
  .act svg { width: 4.4vh; height: 4.4vh; }
  .instar { float: right; color: #E3B341; font-size: 3.6vh; line-height: 11vh; }
  /* Значение настройки у правого края строки - тем же приёмом, что и звезда в списке. */
  .val { float: right; margin-left: 2vw; color: #6BA6F0; font-size: 3.2vh; line-height: 11vh; }
  /* Отбивка перед очисткой данных: единственная строка экрана, которая действует сразу. */
  .gap { height: 4vh; }
  /*
    Шапка «О браузере» (ADR-056). Заголовок экрана здесь не нужен и убран намеренно:
    название и есть заголовок, а два крупных текста подряд читались как два пункта
    списка - замечание пользователя 2026-08-23. Всё выровнено по центру, знак повторяет
    иконку лаунчера (ADR-064) - зазор под кольцом не закрашен, а вырезан цветом фона.
  */
  .hero { text-align: center; margin: 2vh 0 5vh; }
  .hero svg { width: 13vh; height: 13vh; display: block; margin: 0 auto 2vh; }
  .word { font-size: 7vh; font-weight: 700; letter-spacing: 0.14em; line-height: 1; }
  .sub { font-size: 2.4vh; letter-spacing: 0.55em; color: #7D8F9B; margin-top: 0.8vh; }
  .tag {
    font-size: 3vh; color: #7D8F9B; line-height: 1.4;
    max-width: 34em; margin: 2.6vh auto 0;
  }
  /* Факты стоят колонкой по центру страницы, но внутри выровнены влево: иначе подписи
     и значения гуляли бы друг относительно друга. */
  .facts { display: inline-block; text-align: left; margin: 4.5vh auto 0; }
  .fact { display: flex; align-items: baseline; margin-bottom: 1.2vh; }
  .fact .t1 { font-size: 3.2vh; color: #7D8F9B; width: 11em; }
  .fact .val { float: none; line-height: normal; margin: 0; color: #E8EEF2; }
  .act:hover { border-color: #6BA6F0; background: #2C4A73; color: #E8EEF2; }
  .star.on { color: #E3B341; }
  .del:hover { border-color: #E5534B; color: #E5534B; }
  .pin:hover, .book:hover { border-color: #6BA6F0; color: #6BA6F0; }
  /* Вкладки экрана истории. Открытая подчёркнута цветом наведения - тем же, которым
     на этих экранах помечено всё активное, чтобы не заводить второй язык выделения. */
  .tabs { display: flex; gap: 1.2vh; margin: 0 0 1.8vh 0; }
  .tab {
    padding: 1vh 2.4vw; font-size: 3vh; color: #7D8F9B; text-decoration: none;
    border-bottom: 3px solid transparent;
  }
  .tab.on { color: #E8EEF2; border-bottom-color: #6BA6F0; }
  .tab:hover { color: #E8EEF2; background: #2C4A73; }
  .mark {
    display: inline-block; width: 5.6vh; height: 5.6vh; line-height: 5.6vh;
    margin: 2.7vh 1.6vw 0 0; vertical-align: top; border-radius: 6px;
    font-size: 3.2vh; font-weight: 700; color: #0E1216; text-align: center;
  }
  .txt { display: inline-block; vertical-align: top; margin-top: 2vh; max-width: 82%; overflow: hidden; }
  .t1 { display: block; font-size: 3.5vh; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t2 { display: block; font-size: 2.8vh; color: #7D8F9B; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .c0 { background: #E8544A } .c1 { background: #4FB6A5 } .c2 { background: #9146FF }
  .c3 { background: #E3B341 } .c4 { background: #4A8FE8 } .c5 { background: #E86AA8 }
  .c6 { background: #6FBF4A } .c7 { background: #E8874A }
"""

/**
 * Домашняя страница: избранные плитками и полоса последнего сайта (Этап 6, макет
 * `design/home-mockup.html`).
 *
 * **Адреса из хранилища в разметку не попадают вовсе.** Ссылка плитки - это
 * `istok://open/<номер>`, а по какому адресу идти, решает приложение, взяв запись
 * с этим номером у себя. Требование E-5 говорит, что адрес в локальной странице
 * обязан проверяться по схеме, а не только экранироваться - здесь оно выполняется
 * тем, что проверять нечего: `javascript:`-адресу, попавшему в закладки, неоткуда
 * взяться в `href`. Заголовки и хосты подставляются как текст и проходят `htmlEncode`
 * (требование E-4).
 *
 * Раскладка следует утверждённому макету: до трёх элементов - один ряд и плитки крупнее,
 * дальше два ряда по три и плитки мельче; неполный ряд центрируется сам, потому что
 * ряд - строка с выравниванием по центру. Пунктирная плитка с плюсом стоит последней,
 * пока избранных меньше [Storage.MAX_FAVORITES], и исчезает на шести.
 */
internal fun homePage(
    favorites: List<SiteEntry>,
    last: SiteEntry?,
    lastHint: String,
    warnings: List<String>,
    nonce: String,
    editing: Boolean,
    editLabel: String,
    doneLabel: String,
    removeLabel: String,
    renameLabel: String,
    leftLabel: String,
    rightLabel: String,
): String {
    val hasAdd = favorites.size < Storage.MAX_FAVORITES && !editing
    val count = favorites.size + if (hasAdd) 1 else 0
    val size = when {
        count <= 2 -> "solo"
        count == 3 -> "one"
        else -> "two"
    }
    val perRow = if (count <= 3) count else 3

    val body = StringBuilder(2048)
    // Предупреждений может быть сразу два - устаревший движок и отладочная прошивка, -
    // и каждое своей полосой: слитые в одну строку, они читаются как одно длинное.
    for (warning in warnings) {
        body.append("<div class=\"warnbar\">").append(htmlEncode(warning)).append("</div>")
    }
    body.append("<div class=\"tiles\">")
    var index = 0
    while (index < count) {
        body.append("<div class=\"row\">")
        var inRow = 0
        while (inRow < perRow && index < count) {
            when {
                index >= favorites.size -> addTile(body, size)
                editing -> editTile(
                    body, favorites[index], index, size, favorites.size, nonce,
                    removeLabel, renameLabel, leftLabel, rightLabel,
                )
                else -> tile(body, favorites[index], index, size)
            }
            index++
            inRow++
        }
        body.append("</div>")
    }
    body.append("</div>")

    // Кнопка правки живёт на самой странице, а не в меню: избранное правят там же,
    // где на него смотрят, и лишний заход в меню на пульте это два нажатия.
    if (favorites.isNotEmpty()) {
        body.append("<div class=\"edit\"><a class=\"editbtn").append(if (editing) " on" else "")
            .append("\" href=\"").append(INTERNAL_SCHEME).append("://").append(CMD_EDIT)
            .append("\">").append(htmlEncode(if (editing) doneLabel else editLabel))
            .append("</a></div>")
    }

    if (last != null) {
        val host = host(last.url)
        body.append("<div class=\"last\"><a class=\"strip\" href=\"")
            .append(INTERNAL_SCHEME).append("://").append(CMD_OPEN).append('/').append(LAST_ID)
            .append("\"><span class=\"hint\">").append(htmlEncode(lastHint)).append("</span>")
        mark(body, last, host)
        body.append("<span class=\"txt\"><span class=\"t1\">")
            .append(htmlEncode(name(last, host)))
            .append("</span><span class=\"t2\">").append(htmlEncode(host))
            .append("</span></span></a></div>")
    }
    return page(body.toString(), HOME_STYLE)
}

/**
 * Список закладок или истории (Этап 6, шаг 5). Одним генератором на оба экрана:
 * различаются они только заголовком, текстом пустого списка и приставкой в адресе
 * ссылки, а вёрстка у них общая - строка со значком, названием и хостом.
 *
 * Адрес записи в разметку **не попадает**, как и на домашней странице (требование E-5):
 * в `href` идёт приставка источника и номер, а сам адрес приложение берёт у себя.
 * Заголовок и хост чужие, поэтому проходят через [htmlEncode] (требование E-4).
 */
internal fun listPage(
    title: String,
    items: List<SiteEntry>,
    empty: String,
    prefix: Char,
    nonce: String,
    favorites: Set<String> = emptySet(),
    actions: RowActions? = null,
    picking: Boolean = false,
    clearLabel: String? = null,
    tabs: Tabs? = null,
): String {
    val body = StringBuilder(2048)
    body.append("<div class=\"listhead\"><span>").append(htmlEncode(title)).append("</span>")
    // Чистить нечего, пока список пуст, - и кнопки тогда нет.
    if (clearLabel != null && items.isNotEmpty()) {
        body.append("<a class=\"trash\" title=\"").append(htmlEncode(clearLabel))
            .append("\" href=\"").append(INTERNAL_SCHEME).append("://").append(CMD_CLEAR_ASK)
            .append("\">").append(TRASH_ICON).append("</a>")
    }
    body.append("</div>")
    // Две вкладки на экране истории (решение пользователя 2026-08-17). Появляются только
    // когда есть что показать в первой: пока ничего не закреплено, экран выглядит ровно
    // как до этой правки. Закреплённая запись уходит из обычной истории во вкладку - без
    // этого одна и та же страница видна дважды, что и было первым, что пользователь
    // заметил в предыдущем виде с блоком поверх списка.
    if (tabs != null) {
        body.append("<div class=\"tabs\">")
        tab(body, tabs.pinnedLabel, TAB_PINNED, tabs.onPinned)
        tab(body, tabs.historyLabel, TAB_HISTORY, !tabs.onPinned)
        body.append("</div>")
    }
    if (items.isEmpty()) {
        body.append("<div class=\"empty\">").append(htmlEncode(empty)).append("</div>")
        return page(body.toString(), LIST_STYLE)
    }
    body.append("<div class=\"list\">")
    for (index in items.indices) {
        val entry = items[index]
        val host = host(entry.url)
        val starredRow = entry.url in favorites
        body.append("<div class=\"line\"><a class=\"row\" href=\"")
        // В режиме выбора вся строка добавляет закладку в избранное, а не открывает сайт.
        // Так пользователь и жмёт: он пришёл сюда с домашней по кнопке «+», и целиться
        // после этого в маленькую звезду - работа, о которой надо догадаться.
        if (picking) {
            body.append(INTERNAL_SCHEME).append("://").append(CMD_FAVORITE).append('/')
                .append(nonce).append('/').append(index)
        } else {
            body.append(INTERNAL_SCHEME).append("://").append(CMD_OPEN).append('/')
                .append(prefix).append(index)
        }
        body.append("\">")
        mark(body, entry, host)
        body.append("<span class=\"txt\"><span class=\"t1\">")
            .append(htmlEncode(name(entry, host)))
            .append("</span><span class=\"t2\">").append(htmlEncode(host))
            .append("</span></span>")
        // В режиме выбора уже избранные помечены прямо в строке: иначе непонятно,
        // почему нажатие на них ничего не меняет.
        if (picking && starredRow) body.append("<span class=\"instar\">★</span>")
        body.append("</a>")
        if (actions != null) {
            // Ключ идёт в каждую команду, меняющую состояние (H-8). Номер записи - это
            // номер в том самом списке, который сейчас на экране, поэтому после любого
            // действия страница пересобирается заново: иначе вторая команда попала бы
            // по сдвинувшимся номерам.
            //
            // Порядок кнопок один на все списки: сначала то, что запись куда-то кладёт,
            // последней - та, что её убирает. Иначе удаление оказывалось бы на разных
            // экранах в разных местах, а целятся в него курсором по памяти.
            if (actions.pinLabel != null) {
                action(body, CMD_PIN, nonce, "$index", "pin", actions.pinLabel, "+")
            }
            if (actions.favLabel != null && actions.unfavLabel != null) {
                action(
                    body, CMD_FAVORITE, nonce, "$index",
                    if (starredRow) "star on" else "star",
                    if (starredRow) actions.unfavLabel else actions.favLabel,
                    if (starredRow) "★" else "☆",
                )
            }
            if (actions.bookLabel != null) {
                action(body, CMD_MARK, nonce, "$index", "book", actions.bookLabel, BOOKMARK_ICON)
            }
            if (actions.renameLabel != null) {
                action(body, CMD_RENAME, "", "$index", "pen", actions.renameLabel, PEN_ICON)
            }
            if (actions.removeLabel != null) {
                action(body, CMD_REMOVE, nonce, "$index", "del", actions.removeLabel, "✕")
            }
        }
        body.append("</div>")
    }
    body.append("</div>")
    return page(body.toString(), LIST_STYLE)
}

/** Аргумент вкладки в адресе команды: какую половину экрана истории открыть. */
internal const val TAB_PINNED = "p"
internal const val TAB_HISTORY = "h"

/**
 * Вкладки экрана истории. Тем же приёмом, что и [RowActions]: либо они есть целиком,
 * либо их нет вовсе, и раздельными параметрами это разъезжалось бы.
 */
internal class Tabs(
    val pinnedLabel: String,
    val historyLabel: String,
    val onPinned: Boolean,
)

/** Одна вкладка. Открытая - не ссылка: нажимать на то, где уже находишься, незачем. */
private fun tab(body: StringBuilder, label: String, arg: String, active: Boolean) {
    if (active) {
        body.append("<span class=\"tab on\">").append(htmlEncode(label)).append("</span>")
        return
    }
    body.append("<a class=\"tab\" href=\"").append(INTERNAL_SCHEME).append("://")
        .append(CMD_HISTORY).append('/').append(arg).append("\">")
        .append(htmlEncode(label)).append("</a>")
}

/**
 * Подписи кнопок в строке списка: что показано, то и подписано, а чего нет - того нет.
 *
 * Раньше набор был один на все списки и потому обязательный целиком. С ADR-054 наборы
 * разошлись: у закладки правка и удаление, у закреплённой - избранное, закладка и
 * открепление, у строки истории - закрепление, закладка и удаление записи. Поэтому
 * подпись стала способом включить кнопку: пустая подпись - кнопки нет.
 *
 * Объектом, а не россыпью параметров [listPage], по прежней причине: подписей теперь
 * пять, и раздельно они тянули бы за собой ещё и флаг «показывать ли кнопки»,
 * который может разойтись с подписями.
 */
internal class RowActions(
    val favLabel: String? = null,
    val unfavLabel: String? = null,
    val removeLabel: String? = null,
    val renameLabel: String? = null,
    val bookLabel: String? = null,
    val pinLabel: String? = null,
)

/** Строка экрана настроек: что меняем, что сейчас выбрано и куда ведёт нажатие. */
internal class SettingRow(
    val label: String,
    val hint: String,
    val value: String,
    val href: String,
)

/**
 * Экран настроек (Этап 7, ADR-028). Разметка нарочно та же, что у списков: строка
 * с названием, подписью и значением справа. Своего оформления он не заводит - на экране
 * телевизора важно, чтобы наши экраны были похожи друг на друга, а не разнообразны,
 * да и лишний CSS - это лишний вес в dex.
 *
 * Значков-переключателей нет: тумблер на телевизоре не нажимают, в него целятся
 * курсором, поэтому нажатие принимает вся строка, а состояние написано словом.
 *
 * [action] - очистка данных (E-3). Она стоит последней и отделена пустой строкой:
 * это единственное на экране, что делает что-то сразу, а не меняет значение, и на неё
 * нельзя нажать по пути к соседней настройке. Красная она **не** здесь, а на экране
 * подтверждения: краснота уместна там, где решение принимают, а не там, где о нём
 * спрашивают.
 */
internal fun settingsPage(
    title: String,
    rows: List<SettingRow>,
    action: SettingRow,
): String {
    val body = StringBuilder(1024)
    body.append("<div class=\"listhead\"><span>").append(htmlEncode(title)).append("</span></div>")
    body.append("<div class=\"list\">")
    for (row in rows) settingLine(body, row)
    body.append("<div class=\"gap\"></div>")
    settingLine(body, action)
    body.append("</div>")
    return page(body.toString(), LIST_STYLE)
}

/**
 * «О браузере» (ADR-056): что это за программа, кто её сделал и какая стоит версия -
 * и здесь же обновление.
 *
 * Обновление живёт тут, а не в настройках, по просьбе пользователя и по смыслу:
 * настройки - это то, что человек меняет под себя, а версия и её проверка относятся
 * к самой программе. Заодно экран настроек стал короче на две строки.
 *
 * Факты (создатель, тестировщик, лицензия) нарисованы **не строками списка**: строка
 * списка на этих экранах означает «сюда можно нажать», а нажимать здесь нечего.
 */
internal fun aboutPage(
    product: String,
    sub: String,
    tagline: String,
    facts: List<Pair<String, String>>,
    rows: List<SettingRow>,
): String {
    val body = StringBuilder(1024)
    body.append("<div class=\"hero\">").append(MARK_SVG)
        .append("<div class=\"word\">").append(htmlEncode(product)).append("</div>")
        .append("<div class=\"sub\">").append(htmlEncode(sub)).append("</div>")
        .append("<div class=\"tag\">").append(htmlEncode(tagline)).append("</div>")
        .append("<div class=\"facts\">")
    for ((label, value) in facts) {
        body.append("<div class=\"fact\"><span class=\"t1\">").append(htmlEncode(label))
            .append("</span><span class=\"val\">").append(htmlEncode(value))
            .append("</span></div>")
    }
    body.append("</div></div><div class=\"list\">")
    for (row in rows) settingLine(body, row)
    body.append("</div>")
    return page(body.toString(), LIST_STYLE)
}

/**
 * Экран поддержки (ADR-060): кошельки, у каждого свой QR.
 *
 * Устроен сеткой два на два, а не столбиком: четыре записи в столбик уезжают за нижний
 * край, и человек с пультом узнаёт о нижних, только если догадается пролистать.
 * QR при этом остаётся крупным - его снимают телефоном **с расстояния в комнату**.
 *
 * Рядом с каждым QR - **название сети** и полный адрес текстом. Сеть важнее валюты:
 * перевод, отправленный не в ту сеть, теряется, а прочесть адрес глазами человек может
 * захотеть и без телефона.
 *
 * QR рисуется разметкой (`Wallets.kt`), потому что CSP локальных экранов запрещает
 * загружать что бы то ни было. Белое поле вокруг кода обязательно: сканеры требуют
 * светлую подложку и рамку тишины, на тёмном фоне код читается плохо или не читается.
 */
internal fun donatePage(
    product: String,
    sub: String,
    text: String,
    wallets: List<Pair<String, Wallet>>,
): String {
    val body = StringBuilder(16 * 1024)
    // Шапка та же, что на «О браузере» (ADR-056), только мельче. Текст под ней - **один
    // абзац**, продолжающий описание браузера теми же словами: описание отдельно
    // и призыв отдельно, разными цветами, пользователь забраковал 2026-08-24 - читалось
    // как чужой текст, приклеенный к скопированному. Заголовка «Поддержать» нет:
    // название экрана и есть заголовок.
    body.append("<div class=\"hero\">").append(MARK_SVG)
        .append("<div class=\"word\">").append(htmlEncode(product)).append("</div>")
        .append("<div class=\"sub\">").append(htmlEncode(sub)).append("</div>")
        .append("<div class=\"tag\">").append(htmlEncode(text)).append("</div></div>")
    for ((label, wallet) in wallets) {
        val quiet = 2
        val box = wallet.size + quiet * 2
        body.append("<div class=\"coin\"><div class=\"qr\">")
            .append("<svg viewBox=\"0 0 ").append(box).append(" ").append(box)
            .append("\"><rect width=\"").append(box).append("\" height=\"").append(box)
            .append("\" fill=\"#FFFFFF\"/><g transform=\"translate(")
            .append(quiet).append(",").append(quiet).append(")\"><path d=\"")
            .append(wallet.path).append("\" fill=\"#0B0E11\"/></g></svg></div>")
            .append("<div class=\"about\"><div class=\"net\">")
            .append(htmlEncode(label)).append("</div><div class=\"addr\">")
            .append(htmlEncode(wallet.address)).append("</div></div></div>")
    }
    return page(body.toString(), DONATE_STYLE)
}

private const val DONATE_STYLE = """
  html, body { height: 100%; margin: 0; }
  body {
    background: #0B0E11; color: #E8EEF2; font-family: sans-serif;
    padding: 4vh 6vw 6vh; box-sizing: border-box;
  }
  /*
    Шапка повторяет «О браузере» (ADR-056), но ужата: на этом экране под ней стоят
    четыре кода, и всё вместе обязано поместиться без прокрутки.
  */
  .hero { text-align: center; margin: 0 0 3vh; }
  .hero svg { width: 8vh; height: 8vh; display: block; margin: 0 auto 1vh; }
  .word { font-size: 4.4vh; font-weight: 700; letter-spacing: 0.14em; line-height: 1; }
  .sub { font-size: 1.8vh; letter-spacing: 0.55em; color: #7D8F9B; margin-top: 0.6vh; }
  .tag {
    font-size: 2.4vh; color: #A9BAC6; line-height: 1.5;
    max-width: 40em; margin: 1.8vh auto 0;
  }
  /*
    Кошельки идут сеткой два на два, а не столбиком: четыре записи в столбик уезжают
    за нижний край, и человек с пультом узнаёт о существовании нижних, только если
    догадается пролистать. На экране телевизора места хватает - пусть видно всё сразу.
  */
  .coin {
    display: inline-flex; align-items: center; vertical-align: top;
    width: 47%; margin: 0 2% 3vh 0; box-sizing: border-box;
  }
  .qr { width: 22vh; height: 22vh; flex: none; background: #FFFFFF; border-radius: 1vh; }
  .qr svg { width: 100%; height: 100%; display: block; }
  .about { margin-left: 1.6vw; min-width: 0; }
  .net { font-size: 2.8vh; font-weight: 700; margin-bottom: 1.2vh; }
  /* Адрес - моноширинным и с переносом по любому символу: он длиннее строки экрана,
     а рвать его по словам не по чему - слов в нём нет. */
  .addr {
    font-family: monospace; font-size: 2.2vh; color: #6BA6F0;
    line-height: 1.45; word-break: break-all;
  }
"""

private fun settingLine(body: StringBuilder, row: SettingRow) {
    // `href` здесь всегда наша константа, но кодируется наравне с остальным (`B-169`):
    // разное обращение с подстановками - это то, на чём однажды и ошибаются.
    body.append("<div class=\"line\"><a class=\"row\" href=\"").append(htmlEncode(row.href)).append("\">")
        .append("<span class=\"val\">").append(htmlEncode(row.value)).append("</span>")
        .append("<span class=\"txt\"><span class=\"t1\">").append(htmlEncode(row.label))
        .append("</span><span class=\"t2\">").append(htmlEncode(row.hint))
        .append("</span></span></a></div>")
}

/**
 * Корзина - разметкой, а не картинкой и не символом шрифта. Картинку пришлось бы
 * грузить (CSP локальных страниц запрещает всё, кроме собственных стилей), а символ
 * Unicode на старом движке телевизора рисуется пустым квадратом. Цвет берётся
 * от родителя, поэтому наведение красит значок вместе с рамкой.
 */
private const val TRASH_ICON =
    "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">" +
        "<path d=\"M4 7h16M10 7V4h4v3M6.5 7l1 13h9l1-13M10.5 10.5v7M13.5 10.5v7\"/></svg>"

/**
 * Знак браузера (ADR-064) на экранах «О браузере» и «Поддержать» - разметкой, как
 * и остальные наши значки: CSP локальных страниц запрещает всё внешнее, а инлайновый
 * SVG ничего не загружает. Зазор под кольцом вырезан цветом фона страницы, ровно как
 * в иконке лаунчера подложкой, поэтому знак остаётся одноцветным.
 */
private const val MARK_SVG =
    "<svg viewBox=\"0 0 64 64\"><defs><linearGradient id=\"mk\" " +
        "gradientUnits=\"userSpaceOnUse\" x1=\"26\" y1=\"16\" x2=\"38\" y2=\"52\">" +
        "<stop offset=\"0\" stop-color=\"#72B1EB\"/>" +
        "<stop offset=\"1\" stop-color=\"#2A71CE\"/></linearGradient></defs>" +
        "<circle cx=\"32\" cy=\"34\" r=\"18\" fill=\"url(#mk)\"/>" +
        "<ellipse cx=\"32\" cy=\"34\" rx=\"30\" ry=\"9\" transform=\"rotate(-20 32 34)\" " +
        "fill=\"none\" stroke=\"#0E1216\" stroke-width=\"10\"/>" +
        "<ellipse cx=\"32\" cy=\"34\" rx=\"30\" ry=\"9\" transform=\"rotate(-20 32 34)\" " +
        "fill=\"none\" stroke=\"url(#mk)\" stroke-width=\"4.5\"/></svg>"

/**
 * Лента закладки (ADR-054) - разметкой, как корзина и карандаш. Звезда здесь занята
 * избранным, поэтому у закладки свой знак, а не второй оттенок звезды: два похожих
 * значка в одной строке на телевизоре различаются хуже, чем две разные фигуры.
 */
private const val BOOKMARK_ICON =
    "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">" +
        "<path d=\"M7 4h10v16l-5-4.5L7 20V4z\"/></svg>"

/** Карандаш - по тем же причинам разметкой. Стоит и в строке закладки, и на плитке. */
private const val PEN_ICON =
    "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">" +
        "<path d=\"M4 20h4L19.5 8.5l-4-4L4 16v4zM14.5 5.5l4 4\"/></svg>"

/**
 * Кнопка действия в строке списка или на плитке. [nonce] пуст у команд, которые ничего
 * не меняют: тогда ключ в адрес не попадает вовсе, а не идёт туда пустым сегментом.
 */
private fun action(
    body: StringBuilder,
    command: String,
    nonce: String,
    arg: String,
    css: String,
    label: String,
    glyph: String,
) {
    body.append("<a class=\"act ").append(css).append("\" title=\"").append(htmlEncode(label))
        .append("\" href=\"").append(INTERNAL_SCHEME).append("://").append(command).append('/')
    if (nonce.isNotEmpty()) body.append(nonce).append('/')
    body.append(arg).append("\">").append(glyph).append("</a>")
}

private fun tile(body: StringBuilder, entry: SiteEntry, index: Int, size: String) {
    val host = host(entry.url)
    body.append("<a class=\"tile ").append(size).append("\" href=\"")
        .append(INTERNAL_SCHEME).append("://").append(CMD_OPEN).append('/').append(index)
        .append("\">")
    mark(body, entry, host)
    body.append("<span class=\"name\">").append(htmlEncode(name(entry, host))).append("</span></a>")
}

/**
 * Пунктирная плитка с плюсом. Ведёт на экран закладок - добавить в избранное можно
 * только то, что уже сохранено закладкой: своей клавиатуры ради ввода адреса прямо
 * отсюда не нужно, а список закладок для этого и существует.
 */
private fun addTile(body: StringBuilder, size: String) {
    body.append("<a class=\"tile add ").append(size).append("\" href=\"")
        .append(INTERNAL_SCHEME).append("://").append(CMD_BOOKMARKS)
        .append("\"><span class=\"plus\">+</span></a>")
}

/**
 * Плитка в режиме правки: вместо перехода на сайт - три действия над ней самой.
 * Стрелки двигают плитку по списку, крестик убирает из избранного. Каждая команда
 * несёт одноразовый ключ (H-8), а сама плитка перестаёт быть ссылкой на сайт -
 * иначе промах по маленькой кнопке уводил бы со страницы.
 */
private fun editTile(
    body: StringBuilder,
    entry: SiteEntry,
    index: Int,
    size: String,
    total: Int,
    nonce: String,
    removeLabel: String,
    renameLabel: String,
    leftLabel: String,
    rightLabel: String,
) {
    val host = host(entry.url)
    body.append("<span class=\"tile edit ").append(size).append("\">")
    mark(body, entry, host)
    body.append("<span class=\"name\">").append(htmlEncode(name(entry, host))).append("</span>")
    body.append("<span class=\"tools\">")
    if (index > 0) {
        action(body, CMD_MOVE, nonce, "$index/$MOVE_LEFT", "tool", leftLabel, "←")
    }
    action(body, CMD_RENAME, "", "$index", "tool", renameLabel, PEN_ICON)
    action(body, CMD_FAVORITE, nonce, "$index", "tool drop", removeLabel, "✕")
    if (index < total - 1) {
        action(body, CMD_MOVE, nonce, "$index/$MOVE_RIGHT", "tool", rightLabel, "→")
    }
    body.append("</span></span>")
}

/**
 * Квадрат с первой буквой вместо значка сайта. Значки пришлось бы качать, хранить
 * и показывать чужие картинки на своей странице - за отказ от этого мы платим одной
 * буквой, а получаем страницу, которая не ходит в сеть вовсе.
 *
 * Цвет выбирается из палитры по хосту, поэтому один сайт всегда одного цвета,
 * а соседние плитки почти всегда разного.
 */
private fun mark(body: StringBuilder, entry: SiteEntry, host: String) {
    val source = if (entry.title.isNotEmpty()) entry.title else host
    // Первый **символ**, а не первая char: заголовок вида «🌍 Погода» - суррогатная пара,
    // и половина её даёт на плитке пустой квадрат. Заголовки чужие, эмодзи в них обычны.
    val letter = if (source.isEmpty()) {
        "?"
    } else {
        source.substring(0, source.offsetByCodePoints(0, 1)).uppercase()
    }
    var hash = 0
    for (ch in host) hash = hash * 31 + ch.code
    body.append("<span class=\"mark c").append((hash and 0x7fffffff) % MARK_COLORS)
        .append("\">").append(htmlEncode(letter)).append("</span>")
}

private fun name(entry: SiteEntry, host: String): String =
    if (entry.title.isNotEmpty()) entry.title else host

/**
 * Хост для подписи. `www.` отбрасывается: на плитке важно имя сайта, а не то, каким
 * из двух своих адресов он отвечает.
 */
private fun host(url: String): String {
    val host = try {
        Uri.parse(url).host
    } catch (_: Exception) {
        null
    } ?: return url
    val lower = host.lowercase()
    return if (lower.startsWith("www.")) lower.substring(4) else lower
}

private const val MARK_COLORS = 8

/**
 * Экран ошибки. [detail] - недоверенная строка (адрес или системное описание).
 * Кнопки действия у него нет и не будет: там, где пользователь решает, экраны свои -
 * [confirmPage] и [httpConfirmPage], и каждый собирается отдельно.
 */
/**
 * Адрес на экране ошибки - подпись, а не документ. Со снимка живого телевизора
 * (2026-08-24) половину экрана занимал адрес рекламного запроса в сто с лишним
 * символов: `0af2a962...com/e7215796-.../pre-roll/?is_stream=0` (`B-181`). Начало
 * адреса отвечает на единственный вопрос, который здесь задают, - **чей это сайт**;
 * хвост не отвечает ни на что и вытесняет с экрана объяснение.
 */
private fun shortDetail(detail: String): String =
    if (detail.length <= 96) detail else detail.take(95) + "…"

internal fun errorPage(
    title: String,
    message: String,
    detail: String? = null,
    severe: Boolean = false,
): String {
    val head = if (severe) "<h1 class=\"bad\">" else "<h1 class=\"warn\">"
    val body = StringBuilder(512)
    body.append(head).append(htmlEncode(title)).append("</h1>")
    body.append("<p>").append(htmlEncode(message)).append("</p>")
    if (detail != null) {
        body.append("<div class=\"detail\">").append(htmlEncode(shortDetail(detail))).append("</div>")
    }
    return page(body.toString())
}

/**
 * Подтверждение необратимого действия. Вызывающих два: чистка истории и очистка
 * данных браузера (`LocalScreens.askClearHistory` и `askWipe`).
 *
 * Экраном, а не диалогом платформы: до отдельного окна курсор не дотягивается
 * (требование H-3), и с пульта такой диалог был бы мёртвым. Разрушительная кнопка
 * покрашена, безопасная - обычная: промахнуться курсором по соседнему пункту меню
 * легко, а вернуть удалённое нечем.
 *
 * Обе ссылки собирает приложение, недоверенных строк здесь нет вовсе. Экранируются
 * они всё равно - как в [httpConfirmPage] (`B-71`): разное обращение с одинаковыми
 * по природе строками в двух соседних функциях само по себе заставляет искать
 * причину, которой нет.
 */
internal fun confirmPage(
    title: String,
    message: String,
    actionHref: String,
    actionLabel: String,
    cancelHref: String,
    cancelLabel: String,
    note: String? = null,
): String = page(
    "<h1 class=\"warn\">" + htmlEncode(title) + "</h1>" +
        "<p>" + htmlEncode(message) + "</p>" +
        // Отдельной красной строкой и только там, где последствие выходит за рамки самой
        // операции: очистка закрывает браузер, и человек должен узнать это до нажатия.
        (if (note != null) "<p class=\"bad\">" + htmlEncode(note) + "</p>" else "") +
        "<div><a class=\"act\" href=\"" + htmlEncode(cancelHref) + "\">" +
        htmlEncode(cancelLabel) + "</a>" +
        "<a class=\"act danger\" href=\"" + htmlEncode(actionHref) + "\">" +
        htmlEncode(actionLabel) + "</a></div>"
)

/**
 * Подтверждение перехода на http (ADR-011).
 *
 * [host] вынесен отдельной крупной строкой намеренно: решение пользователь принимает
 * о том, кому доверить незащищённое соединение, а не о конкретной странице. Полный
 * адрес показан ниже и мелко - в нём хватает места спрятать знакомое имя так,
 * что вся строка прочитается как адрес другого сайта (требование C-5).
 */
internal fun httpConfirmPage(
    title: String,
    message: String,
    host: String,
    url: String,
    actionHref: String,
    actionLabel: String,
): String = page(
    "<h1 class=\"warn\">" + htmlEncode(title) + "</h1>" +
        "<div class=\"host\">" + htmlEncode(host) + "</div>" +
        "<p style=\"margin-top:24px\">" + htmlEncode(message) + "</p>" +
        "<div class=\"detail\">" + htmlEncode(url) + "</div>" +
        "<a class=\"act\" href=\"" + htmlEncode(actionHref) + "\">" + htmlEncode(actionLabel) + "</a>"
)
