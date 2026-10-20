package Akari.NCM.player.api

/**
 * NetEase Cloud Music official endpoints and host routing constants.
 */
internal object NeteaseEndpoints {
    const val HOST_DEFAULT = "https://music.163.com"
    const val HOST_SERVICE = "https://interface.music.163.com"

    // EAPI endpoints (actual URL paths start with /eapi, plain crypto paths start with /api)
    const val PATH_EAPI_CLOUDSEARCH = "/api/cloudsearch/pc"
    const val URL_EAPI_CLOUDSEARCH = "$HOST_SERVICE/eapi/cloudsearch/pc"

    const val PATH_EAPI_SONG_DETAIL = "/api/v3/song/detail"
    const val URL_EAPI_SONG_DETAIL = "$HOST_SERVICE/eapi/v3/song/detail"

    const val PATH_EAPI_SONG_URL = "/api/song/enhance/player/url/v1"
    const val URL_EAPI_SONG_URL = "$HOST_SERVICE/eapi/song/enhance/player/url/v1"

    const val PATH_EAPI_SONG_LYRIC = "/api/song/lyric/v1"
    const val URL_EAPI_SONG_LYRIC = "$HOST_SERVICE/eapi/song/lyric/v1"

    const val PATH_EAPI_PLAYLIST_DETAIL = "/api/v6/playlist/detail"
    const val URL_EAPI_PLAYLIST_DETAIL = "$HOST_SERVICE/eapi/v6/playlist/detail"

    const val PATH_EAPI_USER_PLAYLIST = "/api/user/playlist"
    const val URL_EAPI_USER_PLAYLIST = "$HOST_SERVICE/eapi/user/playlist"

    const val PATH_EAPI_USER_ACCOUNT = "/api/nuser/account/get"
    const val URL_EAPI_USER_ACCOUNT = "$HOST_SERVICE/eapi/nuser/account/get"

    const val PATH_EAPI_USER_DETAIL = "/api/v1/user/detail"
    const val URL_EAPI_USER_DETAIL = "$HOST_SERVICE/eapi/v1/user/detail"

    const val PATH_EAPI_HOMEPAGE_BLOCK = "/api/homepage/block/page"
    const val URL_EAPI_HOMEPAGE_BLOCK = "$HOST_SERVICE/eapi/homepage/block/page"

    const val PATH_EAPI_HOMEPAGE_DRAGON_BALL = "/api/homepage/dragon/ball/static"
    const val URL_EAPI_HOMEPAGE_DRAGON_BALL = "$HOST_SERVICE/eapi/homepage/dragon/ball/static"

    const val PATH_EAPI_RECOMMEND_SONGS = "/api/v3/discovery/recommend/songs"
    const val URL_EAPI_RECOMMEND_SONGS = "$HOST_SERVICE/eapi/v3/discovery/recommend/songs"

    // WEAPI endpoints
    const val URL_WEAPI_LOGIN_QR_KEY = "$HOST_DEFAULT/weapi/login/qrcode/unikey"
    const val URL_WEAPI_LOGIN_QR_CHECK = "$HOST_DEFAULT/weapi/login/qrcode/client/login"
    const val URL_WEAPI_SONG_LIKE = "$HOST_DEFAULT/weapi/radio/like"
    const val URL_WEAPI_PLAYLIST_MANIPULATE = "$HOST_DEFAULT/weapi/playlist/manipulate/tracks"
    const val URL_WEAPI_RECOMMEND_RESOURCE = "$HOST_DEFAULT/weapi/v1/discovery/recommend/resource"
    const val URL_WEAPI_TOP_PLAYLIST_HIGHQUALITY = "$HOST_DEFAULT/weapi/playlist/highquality/list"

    const val QR_LOGIN_URL_PREFIX = "https://music.163.com/login?codekey="
}