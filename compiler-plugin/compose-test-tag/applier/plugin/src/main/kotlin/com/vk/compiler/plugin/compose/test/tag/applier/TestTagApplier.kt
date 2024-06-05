package com.vk.compiler.plugin.compose.test.tag.applier

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.isAnonymous
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.interpreter.toIrConst
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.popLast

internal class TestTagApplier(
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoid() {

    private val transformedCalls = mutableSetOf<IrCall>()

    private val modifierObjectClass by lazy {
        pluginContext.referenceClass(modifierObjectClassId)
    }

    private val thenFunction by lazy {
        pluginContext.referenceFunctions(thenFuncCallableId).firstOrNull()?.owner?.symbol
    }

    private val applyTagFunction by lazy {
        pluginContext.referenceFunctions(applyTagCallableId).firstOrNull()?.owner?.symbol
    }

    private val functionsStack = mutableListOf<IrFunction>()
    private val filesStack = mutableListOf<IrFile>()

    override fun visitFile(declaration: IrFile): IrFile {
        filesStack += declaration
        val result = super.visitFile(declaration)
        filesStack.popLast()
        return result
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        functionsStack += declaration
        val result = super.visitFunction(declaration)
        functionsStack.popLast()
        return result
    }

    override fun visitCall(expression: IrCall): IrExpression =
        super.visitCall(expression.applyTestTagToModifier())


    private fun IrCall.applyTestTagToModifier(): IrCall {
        val applyTagFunction = applyTagFunction
        val lastFile = filesStack.lastOrNull()
        val lastFunction = functionsStack.lastOrNull()?.searchNotAnonymous()

        if (applyTagFunction == null || lastFile == null || lastFunction == null) return this

        if (!symbol.owner.isComposable() || this in transformedCalls) return this

        transformedCalls += this

        return transformModifierArguments(applyTagFunction, lastFile, lastFunction)

    }

    private fun IrCall.transformModifierArguments(
        applyTagFunction: IrSimpleFunctionSymbol,
        lastFile: IrFile,
        lastFunction: IrFunction
    ): IrCall {

        repeat(valueArgumentsCount) { index ->
            val parameter = symbol.owner.valueParameters[index]
            val argumentExpression = retrieveArgumentExpression(parameter, getValueArgument(index))

            if (argumentExpression?.containsModifierWithTestTag() == false && !parameter.isVararg && parameter.isMainComposeModifier()) {

                val modifiedArgumentExpression = modifyExpressionForTestTag(
                    argumentExpression,
                    parameter,
                    applyTagFunction,
                    createTag(this, lastFile, lastFunction)
                )
                if (modifiedArgumentExpression != null) putValueArgument(
                    index,
                    modifiedArgumentExpression
                )
            }
        }
        return this
    }

    private fun modifyExpressionForTestTag(
        argumentExpression: IrExpression,
        parameter: IrValueParameter,
        applyTagFunction: IrSimpleFunctionSymbol,
        tag: String
    ): IrExpression? {

        // Column() -> Column(<default-value>.applyTestTag(tag))
        // Column(Modifier) -> Column(Modifier.applyTestTag(tag))
        // Column(Modifier.fillMaxSize()) -> Column(Modifier.fillMaxSize().applyTestTag(tag))

        // Column(CustomModifier) -> skip
        // Column(modifier) -> skip
        // Column(modifier.fillMaxSize()) -> skip

        // val tmp_modifier: Modifier.Object = Modifier.Object
        // Column(temp_modifier) -> Column(Modifier.applyTestTag(tag))

        // val tmp_modifier: Modifier = {
        //      val tmp: Modifier.Object = Modifier.Object
        //      tmp.fillMaxSize() -> tmp.fillMaxSize().then(Modifier.applyTestTag(tag))
        // }
        // Column(tmp_modifier) -> Column(tmp_modifier)

        // val tmp_modifier: Modifier = Modifier.fillMaxSize().applyTestTag(tag)
        // Column(temp_modifier) -> Column(tmp_modifier)

        return when (argumentExpression) {
            is IrGetValue -> modifyIrGetValue(argumentExpression, parameter, tag, applyTagFunction)
            is IrCall -> modifyIrCall(argumentExpression, parameter, tag, applyTagFunction)
            is IrGetObjectValue -> modifierIrGetObject(argumentExpression, parameter, applyTagFunction, tag)
            else -> null
        }

    }

    private fun modifyIrGetValue(
        argumentExpression: IrGetValue,
        parameter: IrValueParameter,
        tag: String,
        applyTagFunction: IrSimpleFunctionSymbol
    ): IrExpression? {
        // check for temp variables
        if (argumentExpression.symbol.owner.origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) return null

        val variable = argumentExpression.symbol.owner as? IrVariable
        val variableInitializer = variable?.initializer

        if (variableInitializer?.containsModifierWithTestTag() == true) return null

        return when {
            // we cannot cast Modifier to Modifier.Object. so just create new expression
            variableInitializer?.type?.isComposeModifierObject() == true -> {

                val modifierObjectClass = modifierObjectClass ?: return null

                val modifierObject = IrGetObjectValueImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = modifierObjectClass.defaultType,
                    symbol = modifierObjectClass
                )
                modifierIrGetObject(modifierObject, parameter, applyTagFunction, tag)
            }

            variableInitializer is IrBlock -> {
                val lastStatement = variableInitializer.statements.lastOrNull() as? IrExpression
                if (lastStatement != null) {
                    val modifiedStatement =
                        modifyExpressionForTestTag(lastStatement, parameter, applyTagFunction, tag)
                    if (modifiedStatement != null) {
                        variableInitializer.statements[variableInitializer.statements.lastIndex] = modifiedStatement
                    }
                }
                null
            }

            variableInitializer != null -> {
                val modifierInitializer =
                    modifyExpressionForTestTag(variableInitializer, parameter, applyTagFunction, tag)
                if (modifierInitializer != null) {
                    variable.initializer = modifierInitializer
                }
                null
            }
            else -> null
        }
    }

    private fun modifierIrGetObject(
        argumentExpression: IrExpression,
        parameter: IrValueParameter,
        applyTagFunction: IrSimpleFunctionSymbol,
        tag: String
    ): IrCallImpl? {
        if (argumentExpression.type.isComposeModifierObject().not()) return null

        return IrCallImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = parameter.type,
            symbol = applyTagFunction,
            typeArgumentsCount = 0,
            valueArgumentsCount = 1,
        ).apply {
            extensionReceiver = argumentExpression
            putValueArgument(0, tag.toIrConst(pluginContext.irBuiltIns.stringType))
        }
    }

    private fun modifyIrCall(
        argumentExpression: IrCall,
        parameter: IrValueParameter,
        tag: String,
        applyTagFunction: IrSimpleFunctionSymbol
    ): IrExpression? {

        val topmostReceiverCall = argumentExpression.getTopmostReceiverCall()
        val topmostReceiverObject = topmostReceiverCall.extensionReceiver ?: topmostReceiverCall.dispatchReceiver

        if (topmostReceiverObject is IrGetValue) {
            return modifyExpressionChainForIrGetValue(
                argumentExpression,
                parameter,
                applyTagFunction,
                tag,
                topmostReceiverObject
            )
        }

        return modifyExpressionChain(argumentExpression, parameter, applyTagFunction, tag, topmostReceiverObject)
    }

    private fun modifyExpressionChain(
        argumentExpression: IrCall,
        parameter: IrValueParameter,
        applyTagFunction: IrSimpleFunctionSymbol,
        tag: String,
        topmostReceiverObject: IrExpression?
    ): IrCallImpl? {

        if (topmostReceiverObject?.type?.isComposeModifierObject() != true) return null

        val thenFunction = thenFunction
        val modifierObjectClass = modifierObjectClass

        if (thenFunction == null || modifierObjectClass == null) return null

        val modifierObject = IrGetObjectValueImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = modifierObjectClass.defaultType,
            symbol = modifierObjectClass
        )

        val applyTagCall = IrCallImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = parameter.type,
            symbol = applyTagFunction,
            typeArgumentsCount = 0,
            valueArgumentsCount = 1,
        ).apply {
            extensionReceiver = modifierObject
            putValueArgument(0, tag.toIrConst(pluginContext.irBuiltIns.stringType))
        }

        return IrCallImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = parameter.type,
            symbol = thenFunction,
            typeArgumentsCount = 0,
            valueArgumentsCount = 1,
        ).apply {
            dispatchReceiver = argumentExpression
            putValueArgument(0, applyTagCall)
        }
    }

    private fun modifyExpressionChainForIrGetValue(
        argumentExpression: IrCall,
        parameter: IrValueParameter,
        applyTagFunction: IrSimpleFunctionSymbol,
        tag: String,
        topmostReceiverObject: IrGetValue
    ): IrCallImpl? {

        val thenFunction = thenFunction
        val topExpression = modifyIrGetValue(
            topmostReceiverObject,
            parameter,
            tag,
            applyTagFunction
        )

        if (thenFunction == null || topExpression == null) return null

        return IrCallImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = parameter.type,
            symbol = thenFunction,
            typeArgumentsCount = 0,
            valueArgumentsCount = 1,
        ).apply {
            dispatchReceiver = argumentExpression
            putValueArgument(0, topExpression)
        }
    }

    private fun IrCall.getTopmostReceiverCall(): IrCall =
        when (val receiver = extensionReceiver ?: dispatchReceiver) {
            is IrCall -> receiver.getTopmostReceiverCall()
            else -> this
        }

    private fun IrFunction.isComposable(): Boolean {
        return hasAnnotation(Composable)
    }

    private fun IrValueParameter.isMainComposeModifier(): Boolean {
        return name.asString() == "modifier" && type.isComposeModifier()
    }

    private fun IrType.isComposeModifier(): Boolean {
        return classFqName?.asString() == MODIFIER
    }

    private fun IrType.isComposeModifierObject(): Boolean =
        classFqName?.asString() == MODIFIER_COMPANION

    private fun IrFunction.searchNotAnonymous(): IrFunction? {
        if (!name.isAnonymous) return this

        val parent = parent
        if (parent is IrFunction) return parent.searchNotAnonymous()

        return null
    }

    private fun IrExpression.containsModifierWithTestTag(): Boolean {
        return this.dumpKotlinLike().contains(testTagRegex)
    }

    private fun createTag(
        irCall: IrCall,
        lastFile: IrFile,
        lastFunction: IrFunction,
    ) = "${lastFile.name}-" +
            "${lastFunction.name}(${lastFunction.startOffset})-" +
            "${irCall.symbol.owner.name}(${irCall.startOffset})"

    private fun retrieveArgumentExpression(
        param: IrValueParameter,
        value: IrExpression?
    ): IrExpression? =
        when {
            value == null -> param.defaultValue?.expression?.takeIf { it.isValidValueExpression() }
            value.isValidValueExpression() -> value
            else -> null
        }

    private fun IrExpression.isValidValueExpression(): Boolean {
        return this is IrCall || this is IrGetObjectValue || this is IrGetValue
    }

    private companion object {
        const val MODIFIER = "androidx.compose.ui.Modifier"
        const val MODIFIER_COMPANION = "${MODIFIER}.Companion"
        val Composable = FqName( "androidx.compose.runtime.Composable")
        val modifierObjectClassId = ClassId(
            FqName("androidx.compose.ui"),
            Name.identifier("Modifier.Companion")
        )
        val thenFuncCallableId = CallableId(
            ClassId(
                FqName("androidx.compose.ui"),
                Name.identifier("Modifier")
            ),
            Name.identifier("then")
        )
        val testTagRegex = "applyTestTag|testTag".toRegex()
        val applyTagCallableId = CallableId(
                FqName("com.vk.compose.test.tag.applier"),
                Name.identifier("applyTestTag")
            )
    }
}