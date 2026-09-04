package icu.minq.memoh.security

import icu.minq.memoh.model.AuthMaterial

interface AuthStore {
    fun read(): AuthMaterial?
    fun write(value: AuthMaterial)
    fun clear()
}
