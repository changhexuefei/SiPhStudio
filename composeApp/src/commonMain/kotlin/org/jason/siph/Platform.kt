package org.jason.siph

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform