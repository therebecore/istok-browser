package io.github.therebecore.istok

import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Ответы на запросы страницы к устройству и к пользователю.
 *
 * Все они здесь отклоняются. Это не заглушка на будущее: браузеру на телевизоре
 * нечего дать сайту из перечисленного, и выдавать это молча по умолчанию - хуже,
 * чем отказать явно.
 */
internal class BrowserChromeClient(
    /** Ход загрузки, 0..100 - для полосы под панелью. */
    private val onProgress: (Int) -> Unit,
    /** Заголовок страницы стал известен или сменился - см. [onReceivedTitle]. */
    private val onTitle: () -> Unit,
    /** Плеер просит весь экран: показать его View поверх всего (ADR-033). */
    private val onFullscreen: (View, CustomViewCallback) -> Unit,
    /** Плеер отдаёт экран обратно - сам или по нашей команде. */
    private val onFullscreenExit: () -> Unit,
) : WebChromeClient() {

    /**
     * Полноэкранное видео (`B-44`, ADR-033). Без этой пары WebView **не отдаёт** видео
     * в полный экран вовсе: плеер зовёт `requestFullscreen`, движок спрашивает нас,
     * ответа нет - и на месте кадра остаётся чёрный прямоугольник. На телевизоре это
     * не мелочь оформления, а весь сценарий: фильм смотрят на весь экран.
     *
     * Свою View плеер даёт нам сам, вместе с обратным вызовом; наше дело - показать её
     * поверх всего и вернуть экран, когда попросят.
     */
    override fun onShowCustomView(view: View, callback: CustomViewCallback) =
        onFullscreen(view, callback)

    override fun onHideCustomView() = onFullscreenExit()

    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

    /**
     * Заголовок приходит **после** коммита документа, а у страниц, меняющих его на ходу
     * (почти любое приложение на одной странице), - и вовсе спустя секунды. Без этого
     * сигнала такой сайт попадал в историю под именем хоста: на момент записи заголовка
     * ещё не было. Механизм повторной записи уже есть - запись с тем же адресом
     * и новым заголовком схлопывается при показе в последнюю.
     */
    override fun onReceivedTitle(view: WebView, title: String) = onTitle()

    /**
     * Требование A-4: камера, микрофон и MIDI отклоняются. Диалог не показываем -
     * на телевизоре нет сценария, в котором ответ был бы «да».
     *
     * **Исключение одно - защищённое воспроизведение (`PROTECTED_MEDIA_ID`), ADR-032.**
     * Это ключ к DRM: без него видео на сервисах с защищённым содержимым не играет
     * вообще, и выглядит это как «плеер не грузится» (`B-44`, найдено на живом
     * телевизоре). Браузер для фильмов, который не умеет показывать фильмы, бессмысленен.
     *
     * Выдаётся **только** этот ресурс и только он: если сайт просит DRM вместе
     * с камерой, камеру он не получит - `grant` вызывается с отфильтрованным списком,
     * а не с тем, что пришло в запросе.
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val media = request.resources
            .filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            .toTypedArray()
        if (media.isEmpty()) request.deny() else request.grant(media)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        // retain = false: отказ не запоминается на диске, сайт просто не получает координат
        callback.invoke(origin, false, false)
    }

    /**
     * Выбор файла на устройстве (`<input type="file">`). Отвечаем `false`: сайт получает
     * пустой результат, системный диалог не открывается. У браузера на телевизоре нет
     * ни доступа к файлам, ни разрешения на него, ни способа выбрать файл пультом.
     *
     * Это и есть поведение по умолчанию, но выставлено оно явно: default WebView
     * менялся от версии к версии, и полагаться на него в вопросах доступа к устройству
     * скилл `webview-security` прямо запрещает.
     */
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = false

    /**
     * JavaScript-диалоги подавляются: `alert` ничего не показывает, `confirm` отвечает
     * «нет», `prompt` возвращает пустой результат.
     *
     * Причина - не безопасность, а управляемость: системный диалог WebView рассчитан
     * на палец, и на пульте пользователь может не суметь его закрыть, а страница
     * до ответа заблокирована. Собственные диалоги появятся на Этапе 5 вместе
     * с экранной клавиатурой, без которой `prompt` всё равно нечем заполнить.
     */
    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean {
        result.cancel()
        return true
    }

    /**
     * «Покинуть страницу?» перед уходом с неё. Здесь единственный из диалогов, где
     * подавление означает `confirm`, а не `cancel`: с `cancel` страница запрещает уход,
     * и пользователь, которому нечем ответить на невидимый вопрос, застревает на сайте.
     * Уход - то, что он и просил, нажав «назад».
     */
    override fun onJsBeforeUnload(
        view: WebView,
        url: String,
        message: String,
        result: JsResult,
    ): Boolean {
        result.confirm()
        return true
    }
}
