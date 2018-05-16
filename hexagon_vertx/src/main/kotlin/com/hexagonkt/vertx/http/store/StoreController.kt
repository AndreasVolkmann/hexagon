package com.hexagonkt.vertx.http.store

import com.hexagonkt.CodedException
import com.hexagonkt.flare
import com.hexagonkt.logger
import com.hexagonkt.vertx.http.acceptFormat
import com.hexagonkt.vertx.http.end
import com.hexagonkt.vertx.http.handleList
import com.hexagonkt.vertx.http.response
import com.hexagonkt.vertx.serialization.serialize
import com.hexagonkt.vertx.store.Store
import io.vertx.core.Future
import io.vertx.ext.web.RoutingContext
import kotlin.reflect.jvm.javaType

class StoreController<T : Any, K : Any>(val store: Store<T, K>) {

    fun insert(context: RoutingContext) {
        context.handleList(store.type) {
            when (it.size) {
                0 -> throw CodedException(400, "Entity expected")
                1 -> store.insertOne(it.first())
                else -> store.insertMany(it)
            }
        }
    }

    fun replace(context: RoutingContext) {
        context.handleList(store.type) {
            when (it.size) {
                0 -> throw CodedException(400, "Entity expected")
                1 -> store.replaceOne(it.first())
                else -> store.replaceMany(it)
            }
        }
    }

    fun update(context: RoutingContext) {
        val pattern = createPatternMap(context)
        val updates = createUpdate(context)
        store.updateMany(pattern, updates).setHandler {
            context.end(200, "")
        }
    }

    fun updateOne(context: RoutingContext) {
        val key = parseKey(context) ?: error("Required key not found")
        val updates = createUpdate(context)
        logger.flare(updates)
        store.updateOne(key, updates).setHandler {
            context.end(200, "")
        }
    }

    fun deleteOne(context: RoutingContext) {
        store.deleteOne(parseKey(context) ?: error("Required key not found")).setHandler {
            if (it.result()) context.end(200, "")
            else context.end(404, "")
        }
    }

    fun deleteByPattern(context: RoutingContext) {
        store.deleteMany(createPatternMap(context)).setHandler {
            context.end(204, "Deleted: ${it.result()}")
        }
    }

    fun drop(context: RoutingContext) {
        store.drop().setHandler {
            context.end(200, "Store deleted")
        }
    }

    fun findOne(context: RoutingContext) {
        val projection = createProjection(context)
        val key = parseKey(context) ?: error("Required key not found")
        val handler =
            if (projection.isEmpty()) store.findOne(key)
            else store.findOne(key, projection)

        handler.setHandler {
            val entity = it.result()
            if (entity != null) context.end(200, entity.serialize(context.acceptFormat()))
            else context.end(404, "")
        }
    }

    fun findByPattern(context: RoutingContext) {
        val projection = createProjection(context)

        val pattern = createPatternMap(context)
        val max = getIntegerParam(context, "max")
        val offset = getIntegerParam(context, "offset")

        val sort = createSort(context)

        store.count(pattern)
            .compose { count ->
                context.response.putHeader("X-total", count.toString())

                val future =
                    if (projection.isEmpty()) store.findMany(pattern, max, offset, sort)
                    else store.findMany(pattern, projection, max, offset, sort)

                @Suppress("UNCHECKED_CAST")
                future as Future<List<*>>
            }
            .setHandler {
                val result = it.result()
                context.end(200, result.serialize(context.acceptFormat()))
            }
    }

    fun count(context: RoutingContext) {
        store.count(createPatternMap(context)).setHandler {
            context.end(200, it.result().toString())
        }
    }

    private fun createSort(context: RoutingContext): Map<String, Boolean> {
        val include = context.request().getParam("sort") ?: ""
        return include
            .split(",".toRegex())
            .dropLastWhile { it.isEmpty() }
            .map { it.trim() }
            .map {
                if (it.startsWith("-")) it.substring(1).trim() to true
                else it.trim() to false
            }
            .toMap()
    }

    private fun createProjection(context: RoutingContext): List<String> {
        val include = context.request().getParam("include") ?: ""
        return include.split(",".toRegex()).dropLastWhile { it.isEmpty() }
    }

    private fun createPatternMap(context: RoutingContext): Map<String, List<*>> {
        return createPattern(context).toMap()
    }

    private fun createPattern(context: RoutingContext): List<Pair<String, List<*>>> {
        return context.request().params()
            .filter { it -> store.fields.contains(it.key) }
            .map { param ->
                val key = param.key
                val values = param.value.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                key to values
            }
    }

    private fun createUpdate(context: RoutingContext): Map<String, *> {
        return context.request().params()
            .filter { it -> it.key.endsWith(':') }
            .map { it -> it.key.removeSuffix(":") to it.value }
            .filter { it -> store.fields.contains(it.first) }
            .toMap()
    }

    private fun getIntegerParam(context: RoutingContext, name: String): Int? {
        val integer = context.request().getParam(name)
        return if (integer == null) null else Integer.valueOf(integer)
    }

    private fun parseKey(context: RoutingContext): K? {
        return parseKey(context.request().getParam("id"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseKey(keyObject: Any): K? =
        when (store.key.returnType.javaType) {
            keyObject.javaClass -> keyObject
            String::class.java -> keyObject.toString()
            Int::class.java -> Integer.valueOf(keyObject.toString())
            else -> error("Unsupported key type: ${store.key.returnType.javaType.typeName}")
        } as K?
}
