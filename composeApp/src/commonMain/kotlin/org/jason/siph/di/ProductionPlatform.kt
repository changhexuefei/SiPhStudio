package org.jason.siph.di

import org.jason.siph.domain.production.AuditHasher
import org.jason.siph.domain.production.ProductionRepository

expect fun createPlatformProductionRepository(): ProductionRepository

expect fun createPlatformAuditHasher(): AuditHasher
