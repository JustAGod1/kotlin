/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.isInner
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.calls.TowerDataKind.*
import org.jetbrains.kotlin.fir.resolve.transformers.ReturnTypeCalculator
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction.NONE
import org.jetbrains.kotlin.fir.scopes.impl.FirLocalScope
import org.jetbrains.kotlin.fir.types.ConeIntegerLiteralType
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.HIDES_MEMBERS_NAME_LIST

enum class TowerDataKind {
    EMPTY,       // Corresponds to stub tower level which is replaced by receiver-related level
    TOWER_LEVEL  // Corresponds to real tower level which may process elements itself
}

class FirTowerResolver(
    val typeCalculator: ReturnTypeCalculator,
    val components: BodyResolveComponents,
    resolutionStageRunner: ResolutionStageRunner,
    val topLevelScopes: List<FirScope>,
    val localScopes: List<FirLocalScope>
) {

    val session: FirSession get() = components.session

    private fun processImplicitReceiver(
        towerDataConsumer: TowerDataConsumer,
        implicitReceiverValue: ImplicitReceiverValue<*>,
        nonEmptyLocalScopes: List<FirLocalScope>,
        oldGroup: Int
    ): Int {
        var group = oldGroup
        // Member (no explicit receiver) / extension member (with explicit receiver) access via implicit receiver
        // class Foo(val x: Int) {
        //     fun Bar.baz() {}
        //     fun test() { x }
        //     fun test(b: Bar) { b.baz() }
        // }
        towerDataConsumer.consume(
            TOWER_LEVEL,
            MemberScopeTowerLevel(session, components, dispatchReceiver = implicitReceiverValue, scopeSession = components.scopeSession),
            group++
        )

        // class Foo {
        //     fun foo(block: Foo.() -> Unit) {
        //         block()
        //     }
        // }
        // invokeExtension on local variable
        towerDataConsumer.consume(
            EMPTY,
            TowerScopeLevel.OnlyImplicitReceiver(implicitReceiverValue),
            group++
        )
        //TowerData.OnlyImplicitReceiver(implicitReceiver).process()?.let { return it }

        // Same receiver is dispatch & extension
//        class Foo {
//            fun Foo.bar() {}
//            fun test() { bar() }
//        }
        towerDataConsumer.consume(
            TOWER_LEVEL,
            MemberScopeTowerLevel(
                session, components,
                dispatchReceiver = implicitReceiverValue,
                implicitExtensionReceiver = implicitReceiverValue,
                scopeSession = components.scopeSession
            ),
            group++
        )

        for (scope in nonEmptyLocalScopes) {
            // Local scope extensions via implicit receiver
            // class Foo {
            //     fun test() {
            //         fun Foo.bar() {}
            //         bar()
            //     }
            // }
            towerDataConsumer.consume(
                TOWER_LEVEL,
                ScopeTowerLevel(session, components, scope, implicitExtensionReceiver = implicitReceiverValue),
                group++
            )
        }

        var blockDispatchReceivers = false

        for (implicitDispatchReceiverValue in implicitReceiverValues) {
            val implicitScope = implicitDispatchReceiverValue.implicitScope
            if (implicitScope != null) {
                // Extensions in outer object
                //  object Outer {
                //     fun Nested.foo() {}
                //     class Nested {
                //         fun test() { foo() }
                //     }
                // }
                towerDataConsumer.consume(
                    TOWER_LEVEL,
                    ScopeTowerLevel(session, components, implicitScope, implicitExtensionReceiver = implicitReceiverValue),
                    group++
                )
            }
            if (implicitDispatchReceiverValue is ImplicitDispatchReceiverValue) {
                val implicitCompanionScopes = implicitDispatchReceiverValue.implicitCompanionScopes
                for (implicitCompanionScope in implicitCompanionScopes) {
                    // Extension in companion
                    // class My {
                    //     companion object { fun My.foo() {} }
                    //     fun test() { foo() }
                    // }
                    towerDataConsumer.consume(
                        TOWER_LEVEL,
                        ScopeTowerLevel(session, components, implicitCompanionScope, implicitExtensionReceiver = implicitReceiverValue),
                        group++
                    )
                }
                if (blockDispatchReceivers) {
                    continue
                }
                if ((implicitDispatchReceiverValue.boundSymbol.fir as? FirRegularClass)?.isInner == false) {
                    blockDispatchReceivers = true
                }
            }
            if (implicitDispatchReceiverValue !== implicitReceiverValue) {
                // Two different implicit receivers (dispatch & extension)
                // class A
                // class B {
                //     fun A.foo() {}
                // }
                // fun test(a: A, b: B) {
                //     with(a) { with(b) { foo() } }
                // }
                towerDataConsumer.consume(
                    TOWER_LEVEL,
                    MemberScopeTowerLevel(
                        session, components,
                        scopeSession = components.scopeSession,
                        dispatchReceiver = implicitDispatchReceiverValue,
                        implicitExtensionReceiver = implicitReceiverValue
                    ),
                    group++
                )
            }
        }

        return group
    }

    private fun processTopLevelScope(
        towerDataConsumer: TowerDataConsumer,
        topLevelScope: FirScope,
        oldGroup: Int,
        extensionsOnly: Boolean = false
    ): Int {
        var group = oldGroup
        // Top-level extensions via implicit receiver
        // fun Foo.bar() {}
        // class Foo {
        //     fun test() { bar() }
        // }
        for (implicitReceiverValue in implicitReceiverValues) {
            if (towerDataConsumer.consume(
                    TOWER_LEVEL,
                    ScopeTowerLevel(
                        session, components, topLevelScope,
                        implicitExtensionReceiver = implicitReceiverValue,
                        extensionsOnly = extensionsOnly
                    ),
                    group++
                ) == NONE
            ) {
                return group
            }
        }
        // Member of top-level scope & importing scope
        // val x = 0
        // fun test() { x }
        towerDataConsumer.consume(TOWER_LEVEL, ScopeTowerLevel(session, components, topLevelScope), group++)
        return group
    }

    fun reset() {
        collector.newDataSet()
    }

    val collector = CandidateCollector(components, resolutionStageRunner)
    private lateinit var towerDataConsumer: TowerDataConsumer
    private lateinit var implicitReceiverValues: List<ImplicitReceiverValue<*>>

    fun runResolver(
        implicitReceiverValues: List<ImplicitReceiverValue<*>>, info: CallInfo,
        collector: CandidateCollector = this.collector
    ): CandidateCollector {
        this.implicitReceiverValues = implicitReceiverValues
        towerDataConsumer = when (info.callKind) {
            CallKind.VariableAccess -> {
                createVariableAndObjectConsumer(session, info.name, info, components, collector)
            }
            CallKind.Function -> {
                createFunctionConsumer(session, info.name, info, components, collector, this)
            }
            CallKind.CallableReference -> {
                if (info.stubReceiver == null) {
                    createCallableReferencesConsumer(session, info.name, info, components, collector)
                } else {
                    PrioritizedTowerDataConsumer(
                        collector,
                        createCallableReferencesConsumer(
                            session, info.name, info.replaceExplicitReceiver(info.stubReceiver), components, collector
                        ),
                        createCallableReferencesConsumer(
                            session, info.name, info, components, collector
                        )
                    )
                }
            }
            else -> throw AssertionError("Unsupported call kind in tower resolver: ${info.callKind}")
        }
        val shouldProcessExtensionsBeforeMembers =
            info.callKind == CallKind.Function && info.name in HIDES_MEMBERS_NAME_LIST
        val shouldProcessExplicitReceiverScopeOnly =
            info.callKind == CallKind.Function && info.explicitReceiver?.typeRef?.coneTypeSafe<ConeIntegerLiteralType>() != null

        var group = 0

        // Specific case when extension should be processed before members (Kotlin forEach vs Java forEach)
        if (shouldProcessExtensionsBeforeMembers) {
            for (topLevelScope in topLevelScopes) {
                group = processTopLevelScope(towerDataConsumer, topLevelScope, group, extensionsOnly = true)
            }
        }

        // Member of explicit receiver' type (this stage does nothing without explicit receiver)
        // class Foo(val x: Int)
        // fun test(f: Foo) { f.x }
        towerDataConsumer.consume(EMPTY, TowerScopeLevel.Empty, group++)

        if (shouldProcessExplicitReceiverScopeOnly) {
            return collector
        }

        // Member of local scope
        // fun test(x: Int) = x
        val nonEmptyLocalScopes = mutableListOf<FirLocalScope>()
        for (scope in localScopes) {
            if (towerDataConsumer.consume(TOWER_LEVEL, ScopeTowerLevel(session, components, scope), group++) != NONE) {
                nonEmptyLocalScopes += scope
            }
        }

        var blockDispatchReceivers = false

        // Member of implicit receiver' type *and* relevant scope
        for (implicitReceiverValue in implicitReceiverValues) {
            if (!blockDispatchReceivers || implicitReceiverValue !is ImplicitDispatchReceiverValue) {
                // Direct use of implicit receiver (see inside)
                group = processImplicitReceiver(towerDataConsumer, implicitReceiverValue, nonEmptyLocalScopes, group)
            }
            val implicitScope = implicitReceiverValue.implicitScope
            if (implicitScope != null) {
                // Regular implicit receiver scope (outer objects, statics)
                // object Outer {
                //     val x = 0
                //     class Nested { val y = x }
                // }
                towerDataConsumer.consume(TOWER_LEVEL, ScopeTowerLevel(session, components, implicitScope), group++)
            }
            if (implicitReceiverValue is ImplicitDispatchReceiverValue) {
                val implicitCompanionScopes = implicitReceiverValue.implicitCompanionScopes
                for (implicitCompanionScope in implicitCompanionScopes) {
                    // Companion scope bound to implicit receiver scope
                    // class Outer {
                    //     companion object { val x = 0 }
                    //     class Nested { val y = x }
                    // }
                    towerDataConsumer.consume(TOWER_LEVEL, ScopeTowerLevel(session, components, implicitCompanionScope), group++)
                }
                if ((implicitReceiverValue.boundSymbol.fir as? FirRegularClass)?.isInner == false) {
                    blockDispatchReceivers = true
                }
            }
        }

        for (topLevelScope in topLevelScopes) {
            group = processTopLevelScope(towerDataConsumer, topLevelScope, group)
        }

        return collector
    }
}
