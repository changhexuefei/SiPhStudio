package org.jason.siph.persistence

import javax.naming.NamingEnumeration
import javax.naming.directory.InitialDirContext

internal inline fun <R> InitialDirContext.use(block: (InitialDirContext) -> R): R {
    try {
        return block(this)
    } finally {
        runCatching { close() }
    }
}

internal inline fun <T, R> NamingEnumeration<T>.use(block: (NamingEnumeration<T>) -> R): R {
    try {
        return block(this)
    } finally {
        runCatching { close() }
    }
}
