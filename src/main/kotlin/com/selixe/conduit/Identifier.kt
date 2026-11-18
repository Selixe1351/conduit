package com.selixe.conduit

/**
 * Marks the constructor property that identifies a data class.
 *
 * It is stored as MongoDB's `_id` and used by [Store.get], [Store.update] and [Store.delete].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Identifier
