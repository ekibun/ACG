package soko.ekibun.acg

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform