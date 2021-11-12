package com.github.mtps.kafka

import ch.qos.logback.classic.Level
import org.apache.commons.lang3.builder.EqualsBuilder
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.utils.Utils
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.concurrent.thread
import kotlin.math.ceil

const val version = 1

// int(4):version + uuid(16):uuid + int(4):chunkNum + int(4):chunkCount
private const val headerSize = 28

data class ChunkHeader(val version: Int, val uuid: UUID, val chunkNum: Int, val chunkCount: Int) {
    companion object {
        fun unpack(bytes: ByteArray): Pair<ChunkHeader, ByteArray> {
            val bb = ByteBuffer.wrap(bytes)
            val header = ChunkHeader(bb.int, bb.uuid, bb.int, bb.int)
            val payload = ByteArray(bb.remaining())
            bb.get(payload)
            return header to payload
        }
    }

    fun pack(): ByteArray {
        val bb = ByteBuffer.allocate(headerSize)
        bb.putInt(version)
        bb.putUuid(uuid)
        bb.putInt(chunkNum)
        bb.putInt(chunkCount)
        return bb.array()
    }
}

private fun ByteArray.chunk(uuid: UUID, maxChunkSize: Int): List<ByteArray> {
    val count = ceil(size / maxChunkSize.toDouble()).toInt()
    val wrapped = ByteBuffer.wrap(this)
    return (0 until count).map { i ->
        val header = ChunkHeader(version, uuid, i, count).pack()
        val chunkSize = (if ((wrapped.remaining() + headerSize) > maxChunkSize) maxChunkSize else wrapped.remaining() + headerSize)
        val bb = ByteBuffer.allocate(chunkSize)
        val payload = ByteArray(chunkSize - headerSize)
        wrapped.get(payload)

        bb.put(header)
        bb.put(payload)
        bb.array()
    }
}

private val ByteBuffer.uuid: UUID get() = UUID(long, long)

private fun ByteBuffer.putUuid(uuid: UUID): ByteBuffer = apply {
    putLong(uuid.mostSignificantBits)
    putLong(uuid.leastSignificantBits)
}

private fun UUID.toByteArray() = ByteBuffer.allocate(16).putUuid(this).array()

const val KAFKA_INTERNAL_BUFFER_REQUIRED = 48_672
const val DEFAULT_MAX_CHUNK_SIZE_BYTES = (1 * 1024 * 1024) - KAFKA_INTERNAL_BUFFER_REQUIRED

class ChunkedProducer(private val producer: Producer<String, ByteArray>, private val maxChunkSizeBytes: Int = DEFAULT_MAX_CHUNK_SIZE_BYTES) {
    private val log = logger()

    fun send(topic: String, bytes: ByteArray): Future<List<RecordMetadata>> {
        val uuid = UUID.randomUUID()
        val partitions = producer.partitionsFor(topic)
        val partition = Utils.toPositive(Utils.murmur2(uuid.toByteArray())) % partitions.size

        log.info("bytes size: ${bytes.size}")
        val futures = bytes.chunk(uuid, maxChunkSizeBytes).map {
            log.info("chunk size: ${it.size}")
            CompletableFuture.supplyAsync {
                log.info("-- send: ${it.size} bytes")
                producer.send(ProducerRecord(topic, partition, uuid.toString(), it)).get()
            }
        }

        val result = CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.map { it.join() } }
        futures.forEach { f ->
            f.whenComplete { t, ex ->
                if (ex != null) {
                    result.completeExceptionally(ex)
                }
            }
        }
        return result
    }
}

interface ChunkStore {
    fun put(uuid: UUID, offset: Int, count: Int, bytes: ByteArray)
    fun get(uuid: UUID): List<ByteArray>
    fun clear(uuid: UUID)
}

class ChunkHandler(
        private val chunkStore: ChunkStore,
        private val onComplete: (ByteArray) -> Unit
) : ConsumerHandler<String, ByteArray> {
    private val log = logger()

    override fun invoke(p1: ConsumerRecord<String, ByteArray>) {
        val (header, chunk) = ChunkHeader.unpack(p1.value())

        log.info("++ recv: ${chunk.size} bytes")
        chunkStore.put(header.uuid, header.chunkNum, header.chunkCount, chunk)
        val chunks = chunkStore.get(header.uuid).takeIf { it.size == header.chunkCount } ?: return

        val dest = ByteArray(chunks.map { it.size }.sum())

        onComplete(chunks.fold(ByteBuffer.wrap(dest)) { acc, t -> acc.put(t) }.array())
        chunkStore.clear(header.uuid)
    }
}

class InMemoryChunkStore : ChunkStore {
    private data class Chunk(val offset: Int, val count: Int, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            return EqualsBuilder.reflectionEquals(this, other)
        }

        override fun hashCode(): Int {
            return HashCodeBuilder.reflectionHashCode(41, 963, this)
        }
    }

    private val map = mutableMapOf<UUID, MutableMap<Int, Chunk>>()

    override fun put(uuid: UUID, offset: Int, count: Int, bytes: ByteArray) {
        map.getOrPut(uuid) { mutableMapOf() }.putIfAbsent(offset, Chunk(offset, count, bytes))
    }

    override fun get(uuid: UUID): List<ByteArray> {
        return map.getOrPut(uuid) { mutableMapOf() }.map { it.value.bytes }
    }

    override fun clear(uuid: UUID) {
        map.remove(uuid)
    }
}

fun main() {
    logger("io.provenance.timed").level = Level.WARN
    logger("org.apache.kafka").level = Level.WARN
    logger("io.provenance.core.kafka.consumers.KRunnable").level = Level.WARN

    thread {

        val producer = KafkaProducer(mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092" as Any
        ), StringSerializer(), ByteArraySerializer())

        val chunkedProducer = ChunkedProducer(producer)
        val rand = Random()
        while (true) {
            // val b = ByteArray(DEFAULT_MAX_CHUNK_SIZE_BYTES * 15 - 1000)
            // val b = ByteArray(1)
            val b = ByteArray(DEFAULT_MAX_CHUNK_SIZE_BYTES)
            rand.nextBytes(b)
            logger("chunky").info("Sending ${b.size} bytes")
            chunkedProducer.send("chunked", b).get().map {
                logger("chunky").info("Sent to ${it.topic()}-${it.partition()}@${it.offset()}")
            }
            break
        }
    }

    val chunkedConsumerHandler = ChunkHandler(InMemoryChunkStore()) {
        logger("consumer").info("Recv: ${it.size} bytes")
    }

    val kafka = Kafka("test", arrayOf("localhost:9092"), consumerConfigs = mapOf(ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1000 as Any))
    val consumer = kafka
            .newConsumer("tester", "aoeu", StringDeserializer(), ByteArrayDeserializer(), 1)
            .listen("chunked", chunkedConsumerHandler)

    while (true);
}
