package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.InMemoryProductionRepository
import org.jason.siph.domain.production.PortableAuditHasher
import org.jason.siph.domain.production.ProductionRepository

actual fun createPlatformProductionRepository(): ProductionRepository = InMemoryProductionRepository()

actual fun createPlatformAuditHasher(): AuditHasher = PortableAuditHasher()
