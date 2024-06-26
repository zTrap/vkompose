package com.vk.compiler.plugin.recompose.logger

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class RecomposeLoggerIrGeneration(
    private val logModifierChanges: Boolean,
    private val logFunctionChanges: Boolean
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) =
        moduleFragment.transformChildrenVoid(
            RecomposeLogger(
                pluginContext,
                logModifierChanges,
                logFunctionChanges
            )
        )
}