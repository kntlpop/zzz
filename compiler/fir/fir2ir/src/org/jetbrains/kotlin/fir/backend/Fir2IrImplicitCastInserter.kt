/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.baseForIntersectionOverride
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirStubStatement
import org.jetbrains.kotlin.fir.expressions.impl.FirUnitExpression
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.scopes.FakeOverrideTypeCalculator
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.coerceToUnitIfNeeded
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name

class Fir2IrImplicitCastInserter(
    private val components: Fir2IrComponents,
    private val visitor: Fir2IrVisitor
) : Fir2IrComponents by components, FirDefaultVisitor<IrElement, IrElement>() {

    private fun FirTypeRef.toIrType(): IrType = with(typeConverter) { toIrType() }

    private fun ConeKotlinType.toIrType(): IrType = with(typeConverter) { toIrType() }

    override fun visitElement(element: FirElement, data: IrElement): IrElement {
        TODO("Should not be here: ${element::class}: ${element.render()}")
    }

    override fun visitAnnotationCall(annotationCall: FirAnnotationCall, data: IrElement): IrElement = data

    override fun visitAnonymousObject(anonymousObject: FirAnonymousObject, data: IrElement): IrElement = data

    override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction, data: IrElement): IrElement = data

    override fun visitBinaryLogicExpression(binaryLogicExpression: FirBinaryLogicExpression, data: IrElement): IrElement = data

    // TODO: maybe a place to do coerceIntToAnotherIntegerType?
    override fun visitComparisonExpression(comparisonExpression: FirComparisonExpression, data: IrElement): IrElement = data

    override fun visitTypeOperatorCall(typeOperatorCall: FirTypeOperatorCall, data: IrElement): IrElement = data

    override fun visitEqualityOperatorCall(equalityOperatorCall: FirEqualityOperatorCall, data: IrElement): IrElement = data

    override fun <T> visitConstExpression(constExpression: FirConstExpression<T>, data: IrElement): IrElement = data

    override fun visitThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression, data: IrElement): IrElement = data

    override fun visitQualifiedAccessExpression(qualifiedAccessExpression: FirQualifiedAccessExpression, data: IrElement): IrElement = data

    override fun visitResolvedQualifier(resolvedQualifier: FirResolvedQualifier, data: IrElement): IrElement = data

    override fun visitGetClassCall(getClassCall: FirGetClassCall, data: IrElement): IrElement = data

    override fun visitFunctionCall(functionCall: FirFunctionCall, data: IrElement): IrElement = data

    override fun visitCheckNotNullCall(checkNotNullCall: FirCheckNotNullCall, data: IrElement): IrElement = data

    override fun visitCheckedSafeCallSubject(checkedSafeCallSubject: FirCheckedSafeCallSubject, data: IrElement): IrElement = data

    override fun visitSafeCallExpression(safeCallExpression: FirSafeCallExpression, data: IrElement): IrElement = data

    override fun visitStringConcatenationCall(stringConcatenationCall: FirStringConcatenationCall, data: IrElement): IrElement = data

    // TODO: element-wise cast?
    override fun visitArrayOfCall(arrayOfCall: FirArrayOfCall, data: IrElement): IrElement = data

    // TODO: something to do w.r.t. SAM?
    override fun visitLambdaArgumentExpression(lambdaArgumentExpression: FirLambdaArgumentExpression, data: IrElement): IrElement = data

    // TODO: element-wise cast?
    override fun visitNamedArgumentExpression(namedArgumentExpression: FirNamedArgumentExpression, data: IrElement): IrElement = data

    // TODO: element-wise cast?
    override fun visitVarargArgumentsExpression(varargArgumentsExpression: FirVarargArgumentsExpression, data: IrElement): IrElement = data

    // TODO: element-wise cast?
    override fun visitSpreadArgumentExpression(spreadArgumentExpression: FirSpreadArgumentExpression, data: IrElement): IrElement = data

    // ==================================================================================

    override fun visitExpression(expression: FirExpression, data: IrElement): IrElement {
        return when (expression) {
            is FirBlock -> (data as IrContainerExpression).insertImplicitCasts()
            is FirUnitExpression -> (data as IrExpression).let { it.coerceToUnitIfNeeded(it.type, irBuiltIns) }
            else -> data
        }
    }

    override fun visitStatement(statement: FirStatement, data: IrElement): IrElement {
        return when (statement) {
            is FirTypeAlias -> data
            FirStubStatement -> data
            is FirUnitExpression -> (data as IrExpression).let { it.coerceToUnitIfNeeded(it.type, irBuiltIns) }
            is FirBlock -> (data as IrContainerExpression).insertImplicitCasts()
            else -> statement.accept(this, data)
        }
    }

    // ==================================================================================

    override fun visitWhenExpression(whenExpression: FirWhenExpression, data: IrElement): IrElement {
        if (data is IrBlock) {
            return data.insertImplicitCasts()
        }
        val irWhen = data as IrWhen
        if (irWhen.branches.size != whenExpression.branches.size) {
            return data
        }
        val firBranchMap = irWhen.branches.zip(whenExpression.branches).toMap()
        irWhen.branches.replaceAll {
            visitWhenBranch(firBranchMap.getValue(it), it)
        }
        return data
    }

    override fun visitWhenSubjectExpression(whenSubjectExpression: FirWhenSubjectExpression, data: IrElement): IrElement = data

    // TODO: cast `condition` expression to boolean?
    override fun visitWhenBranch(whenBranch: FirWhenBranch, data: IrElement): IrBranch {
        val irBranch = data as IrBranch
        (irBranch.result as? IrContainerExpression)?.let {
            irBranch.result = it.insertImplicitCasts()
        }
        return data
    }

    // TODO: Need to visit lhs/rhs branches?
    override fun visitElvisExpression(elvisExpression: FirElvisExpression, data: IrElement): IrElement = data

    // ==================================================================================

    // TODO: cast `condition` expression to boolean?
    override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop, data: IrElement): IrElement {
        val loop = data as IrDoWhileLoop
        (loop.body as? IrContainerExpression)?.let {
            loop.body = it.insertImplicitCasts()
        }
        return data
    }

    // TODO: cast `condition` expression to boolean?
    override fun visitWhileLoop(whileLoop: FirWhileLoop, data: IrElement): IrElement {
        val loop = data as IrWhileLoop
        (loop.body as? IrContainerExpression)?.let {
            loop.body = it.insertImplicitCasts()
        }
        return data
    }

    override fun visitBreakExpression(breakExpression: FirBreakExpression, data: IrElement): IrElement = data

    override fun visitContinueExpression(continueExpression: FirContinueExpression, data: IrElement): IrElement = data

    // ==================================================================================

    override fun visitTryExpression(tryExpression: FirTryExpression, data: IrElement): IrElement {
        val irTry = data as IrTry
        (irTry.finallyExpression as? IrContainerExpression)?.let {
            irTry.finallyExpression = it.insertImplicitCasts()
        }
        return data
    }

    override fun visitThrowExpression(throwExpression: FirThrowExpression, data: IrElement): IrElement =
        (data as IrThrow).cast(throwExpression, throwExpression.exception.typeRef, throwExpression.typeRef)

    override fun visitBlock(block: FirBlock, data: IrElement): IrElement =
        (data as? IrContainerExpression)?.insertImplicitCasts() ?: data

    override fun visitReturnExpression(returnExpression: FirReturnExpression, data: IrElement): IrElement {
        val irReturn = data as IrReturn
        val expectedType = returnExpression.target.labeledElement.returnTypeRef
        irReturn.value = irReturn.value.cast(returnExpression.result, returnExpression.result.typeRef, expectedType)
        return data
    }

    // ==================================================================================

    internal fun IrExpression.cast(expression: FirExpression, valueType: FirTypeRef, expectedType: FirTypeRef): IrExpression {
        if (this is IrTypeOperatorCall) {
            return this
        }
        return when {
            this is IrContainerExpression -> {
                insertImplicitCasts()
            }
            expectedType.isUnit -> {
                coerceToUnitIfNeeded(type, irBuiltIns)
            }
            valueType.hasEnhancedNullability() && !expectedType.acceptsNullValues() -> {
                insertImplicitNotNullCastIfNeeded(expression)
            }
            // TODO: coerceIntToAnotherIntegerType
            // TODO: even implicitCast call can be here?
            else -> this
        }
    }

    private fun FirTypeRef.acceptsNullValues(): Boolean =
        isMarkedNullable == true || hasEnhancedNullability()

    private fun IrExpression.insertImplicitNotNullCastIfNeeded(expression: FirExpression): IrExpression {
        // [TypeOperatorLowering] will retrieve the source (from start offset to end offset) as an assertion message.
        // Avoid type casting if we can't determine the source for some reasons, e.g., implicit `this` receiver.
        if (expression.source == null) {
            return this
        }
        val castType = type.removeAnnotations {
            it.symbol.owner.parentAsClass.classId == CompilerConeAttributes.EnhancedNullability.ANNOTATION_CLASS_ID
        }
        return IrTypeOperatorCallImpl(
            this.startOffset,
            this.endOffset,
            castType,
            IrTypeOperator.IMPLICIT_NOTNULL,
            castType,
            this
        )
    }

    private fun IrContainerExpression.insertImplicitCasts(): IrContainerExpression {
        if (statements.isEmpty()) return this

        val lastIndex = statements.lastIndex
        statements.forEachIndexed { i, irStatement ->
            if (irStatement !is IrErrorCallExpression && irStatement is IrExpression) {
                if (i != lastIndex) {
                    statements[i] = irStatement.coerceToUnitIfNeeded(irStatement.type, irBuiltIns)
                }
                // TODO: for the last statement, need to cast to the return type if mismatched
            }
        }

        return this
    }

    internal fun IrBlockBody.insertImplicitCasts(): IrBlockBody {
        if (statements.isEmpty()) return this

        statements.forEachIndexed { i, irStatement ->
            if (irStatement !is IrErrorCallExpression && irStatement is IrExpression) {
                statements[i] = irStatement.coerceToUnitIfNeeded(irStatement.type, irBuiltIns)
            }
        }
        return this
    }

    override fun visitExpressionWithSmartcast(expressionWithSmartcast: FirExpressionWithSmartcast, data: IrElement): IrExpression {
        return implicitCastOrExpression(data as IrExpression, expressionWithSmartcast.typeRef)
    }

    internal fun convertToImplicitCastExpression(
        expressionWithSmartcast: FirExpressionWithSmartcast, calleeReference: FirReference
    ): IrExpression {
        val originalExpression = expressionWithSmartcast.originalExpression
        val value = visitor.convertToIrExpression(originalExpression)
        val castTypeRef = expressionWithSmartcast.typeRef
        if (calleeReference !is FirResolvedNamedReference) {
            return implicitCastOrExpression(value, castTypeRef)
        }
        val referencedSymbol = calleeReference.resolvedSymbol
        if (referencedSymbol !is FirPropertySymbol && referencedSymbol !is FirFunctionSymbol) {
            return implicitCastOrExpression(value, castTypeRef)
        }

        val originalTypeRef = expressionWithSmartcast.originalType
        if (castTypeRef is FirResolvedTypeRef && originalTypeRef is FirResolvedTypeRef) {
            val castType = castTypeRef.type
            if (castType is ConeIntersectionType) {
                val unwrappedSymbol = (referencedSymbol as? FirCallableSymbol)?.baseForIntersectionOverride ?: referencedSymbol
                castType.intersectedTypes.forEach {
                    if (it.doesContainReferencedSymbolInScope(unwrappedSymbol, calleeReference.name)) {
                        return implicitCastOrExpression(value, it)
                    }
                }
            }
        }
        return if (originalExpression is FirThisReceiverExpression &&
            originalExpression.calleeReference.boundSymbol is FirAnonymousFunctionSymbol
        ) {
            // If the original is a "this" in a local function and original.type is the same as castType,
            // we still want to keep the cast. See kt-42517
            implicitCast(value, castTypeRef.toIrType())
        } else {
            implicitCastOrExpression(value, castTypeRef.toIrType())
        }
    }

    private fun ConeKotlinType.doesContainReferencedSymbolInScope(
        referencedSymbol: AbstractFirBasedSymbol<*>, name: Name
    ): Boolean {
        val scope = scope(session, components.scopeSession, FakeOverrideTypeCalculator.Forced) ?: return false
        var result = false
        val processor = { it: FirCallableSymbol<*> ->
            if (!result && it == referencedSymbol) {
                result = true
            }
        }
        when (referencedSymbol) {
            is FirPropertySymbol -> scope.processPropertiesByName(name, processor)
            is FirFunctionSymbol -> scope.processFunctionsByName(name, processor)
        }
        return result
    }

    private fun implicitCastOrExpression(original: IrExpression, castType: ConeKotlinType): IrExpression {
        return implicitCastOrExpression(original, castType.toIrType())
    }

    private fun implicitCastOrExpression(original: IrExpression, castType: FirTypeRef): IrExpression {
        return implicitCastOrExpression(original, castType.toIrType())
    }

    internal fun implicitCastOrExpression(original: IrExpression, castType: IrType): IrExpression {
        return original.takeIf { it.type == castType } ?: implicitCast(original, castType)
    }

    private fun implicitCast(original: IrExpression, castType: IrType): IrExpression {
        return IrTypeOperatorCallImpl(
            original.startOffset,
            original.endOffset,
            castType,
            IrTypeOperator.IMPLICIT_CAST,
            castType,
            original
        )
    }
}