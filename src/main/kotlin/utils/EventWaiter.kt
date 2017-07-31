package utils

import main.factory
import main.waiter
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.hooks.SubscribeEvent
import java.util.*
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.events.message.react.MessageReactionAddEvent
import java.util.concurrent.*


class EventWaiter : EventListener {
    val executor = Executors.newScheduledThreadPool(50)
    val gameEvents = CopyOnWriteArrayList<Triple<String, Long, ((Message) -> Unit)> /* ID of channel, Long MS of expiration */>()
    val messageEvents = CopyOnWriteArrayList<Pair<Settings, (Message) -> Unit>>()
    val reactionAddEvents = ConcurrentLinkedDeque<Pair<Settings, (MessageReaction) -> Unit>>()

    @SubscribeEvent
    fun onEvent(e: Event) {
        factory.executor.execute {
            when (e) {
                is GuildMessageReceivedEvent -> {
                    if (e.author.isBot) return@execute
                    messageEvents.forEach { mE ->
                        var cont: Boolean = true
                        val settings = mE.first
                        if (settings.channel != null && settings.channel != e.channel.id) cont = false
                        if (settings.id != null && settings.id != e.author.id) cont = false
                        if (settings.guild != null && settings.guild != e.guild.id) cont = false
                        if (cont) {
                            mE.second.invoke(e.message)
                            messageEvents.remove(mE)
                        }
                    }
                    gameEvents.forEach { game ->
                        if (e.channel.id == game.first) {
                            if (System.currentTimeMillis() < game.second) {
                                factory.executor.execute { game.third.invoke(e.message) }
                            } else gameEvents.remove(game)
                        }
                    }
                }
                is MessageReactionAddEvent -> reactionAddEvents.forEach {
                    rAE ->
                    val settings = rAE.first
                    var cont: Boolean = true
                    if (settings.channel != null && settings.channel != e.channel.id) cont = false
                    if (settings.id != null && settings.id != e.user.id) cont = false
                    if (settings.guild != null && settings.guild != e.guild.id) cont = false
                    if (settings.message != null && settings.message != e.messageId) cont = false
                    if (cont) {
                        rAE.second.invoke(e.reaction)
                    }
                    reactionAddEvents.remove(rAE)
                }
            }
        }
    }

    fun waitForReaction(settings: Settings, consumer: (MessageReaction) -> Unit, time: Int = 60, unit: TimeUnit = TimeUnit.SECONDS, silentExpiration: Boolean = false): Pair<Settings, (MessageReaction) -> Unit> {
        val pair = Pair(settings, consumer)
        reactionAddEvents.add(pair)
        executor.schedule({
            if (reactionAddEvents.contains(pair)) {
                reactionAddEvents.remove(pair)
                if (!silentExpiration) {
                    val channel: TextChannel? = settings.channel?.toChannel()
                    channel?.send(channel.guild.selfMember, "You took too long to add a reaction! [${unit.toSeconds(time.toLong())} seconds]")
                }
            }
        }, time.toLong(), unit)
        return pair
    }

    fun gameChannelWait(channel: String, consumer: (Message) -> Unit, time: Int = 10, unit: TimeUnit = TimeUnit.SECONDS) {
        gameEvents.add(Triple(channel, System.currentTimeMillis() + unit.toMillis(time.toLong()), consumer))
    }

    fun waitForMessage(settings: Settings, consumer: (Message) -> Unit, expiration: (() -> Unit)? = null, time: Int = 20, unit: TimeUnit = TimeUnit.SECONDS, silentExpiration: Boolean = false) {
        val pair = Pair(settings, consumer)
        messageEvents.add(pair)
        executor.schedule({
            if (messageEvents.contains(pair)) {
                messageEvents.remove(pair)
                val channel: TextChannel? = settings.channel?.toChannel()
                if (expiration == null && !silentExpiration) channel?.send(channel.guild.selfMember, "You took too long to respond! [${unit.toSeconds(time.toLong())} seconds]")
                expiration?.invoke()
            }
        }, time.toLong(), unit)
    }

    fun cancel(pair: Pair<Settings, (Message) -> Unit>) {
        messageEvents.remove(pair)
        // TODO("add expiration consumer invocation here")
    }

}

data class Settings(val id: String? = null, val channel: String? = null, val guild: String? = null, val message: String? = null)

fun TextChannel.selectFromList(member: Member, title: String, options: MutableList<String>, consumer: (Int) -> Unit, footerText: String? = null) {
    val embed = embed(title, member)
    val builder = StringBuilder()
    for ((index, value) in options.iterator().withIndex()) {
        builder.append("${Emoji.SMALL_BLUE_DIAMOND} **${index + 1}**: $value\n")
    }
    if (footerText != null) builder.append("\n$footerText\n")
    builder.append("\n__Please type the number corresponding with the choice you want to select__\n")
    val msg = sendReceive(member, embed.setDescription(builder))
    waiter.waitForMessage(Settings(member.user.id, id, guild.id), { message ->
        val option: Int? = message.rawContent.toIntOrNull()?.minus(1)
        if (option == null || (option < 0 || option >= options.size)) {
            send(member, "You sent an invalid response; you had to respond with the **number** of an option")
            return@waitForMessage
        }
        msg?.delete()?.reason("Unnecessary Selection List")?.queue()
        consumer.invoke(option)
    })
}