package commands.music

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import main.managers
import main.playerManager
import main.spotifyApi
import main.waiter
import net.dv8tion.jda.core.audio.AudioSendHandler
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.TextChannel
import utils.*
import utils.music.LocalTrackObj
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class AudioPlayerSendHandler(private val audioPlayer: AudioPlayer) : AudioSendHandler {
    private var lastFrame: AudioFrame? = null

    override fun canProvide(): Boolean {
        lastFrame = audioPlayer.provide()
        return lastFrame != null
    }

    override fun provide20MsAudio(): ByteArray {
        return lastFrame!!.data
    }

    override fun isOpus(): Boolean {
        return true
    }
}

class GuildMusicManager(audioPlayerManager: AudioPlayerManager, var channel: TextChannel?, val guild: Guild) {
    val player: AudioPlayer = audioPlayerManager.createPlayer()
    val scheduler: TrackScheduler = TrackScheduler(this, guild)
    val manager = ArdentMusicManager(player)
    internal val sendHandler: AudioPlayerSendHandler get() = AudioPlayerSendHandler(player)

    init {
        player.addListener(scheduler)
    }
}

class ArdentMusicManager(val player: AudioPlayer) {
    var queue = LinkedBlockingDeque<LocalTrackObj>()
    var current: LocalTrackObj? = null

    fun queue(track: LocalTrackObj) {
        if (!player.startTrack(track.track, true)) queue.offer(track)
        else current = track
    }

    fun nextTrack() {
        val track = queue.poll()
        if (track != null) {
            val set: Boolean = track.track.position != 0.toLong()
            try {
                player.startTrack(track.track, false)
            } catch (e: Exception) {
                player.startTrack(track.track.makeClone(), false)
            }
            if (set && player.playingTrack != null) player.playingTrack.position = track.track.position
            current = track
        } else {
            player.startTrack(null, false)
            current = null
        }
    }

    fun resetQueue() {
        this.queue = LinkedBlockingDeque<LocalTrackObj>()
    }

    fun addToBeginningOfQueue(track: LocalTrackObj?) {
        if (track == null) return
        track.track = track.track.makeClone()
        queue.addFirst(track)
    }

    fun removeAt(num: Int): Boolean {
        val track = queue.toList().getOrNull(num) ?: return false
        queue.removeFirstOccurrence(track)
        return true
    }
}

class TrackScheduler(val guildMusicManager: GuildMusicManager, val guild: Guild) : AudioEventAdapter() {
    var autoplay = true
    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        waiter.executor.schedule({
            if (track.position == 0.toLong() && guild.selfMember.voiceState.inVoiceChannel() && !player.isPaused && player.playingTrack != null && player.playingTrack == track) {
                val queue = guildMusicManager.manager.queue.toList()
                guildMusicManager.player.isPaused = false
                guildMusicManager.manager.resetQueue()
                val current = guildMusicManager.manager.current
                guildMusicManager.manager.nextTrack()
                if (current != null) guildMusicManager.manager.queue(current)
                queue.forEach { guildMusicManager.manager.queue(it) }
            }
        }, 5, TimeUnit.SECONDS)
        autoplay = true
        if (channel?.guild?.getData()?.musicSettings?.announceNewMusic == true) {
            if (guild.selfMember.voiceChannel() != null) {
                val builder = guild.selfMember.embed("Now Playing: {0}".tr(channel!!.guild, track.info.title))
                builder.setThumbnail("https://s-media-cache-ak0.pinimg.com/736x/69/96/5c/69965c2849ec9b7148a5547ce6714735.jpg")
                builder.addField("Title".tr(channel!!.guild), track.info.title, true)
                        .addField("Author".tr(channel!!.guild), track.info.author, true)
                        .addField("Duration".tr(channel!!.guild), track.getDurationFancy(), true)
                        .addField("URL".tr(channel!!.guild), track.info.uri, true)
                        .addField("Is Stream".tr(channel!!.guild), track.info.isStream.toString(), true)
                channel?.send(builder)
            } else {
                player.stopTrack()
            }
        }
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        try {
            if (guild.audioManager.isConnected) {
                if (track.position != 0L) PlayedMusic(guild.id ?: "unknown", track.position / 1000.0 / 60.0 / 60.0).insert("musicPlayed")
                if (track.position != 0L && player.playingTrack == null && manager.queue.size == 0 && guild.getData().musicSettings.autoQueueSongs && guild.selfMember.voiceChannel() != null && autoplay) {
                    try {
                        val get = spotifyApi.search.searchTrack(track.info.title.rmCharacters("()").rmCharacters("[]")
                                .replace("ft.", "").replace("feat", "")
                                .replace("feat.", ""))
                        if (get.items.isEmpty()) {
                            (channel ?: manager.getChannel())?.send("Couldn't find this song in the Spotify database, no autoplay available.".tr(channel!!.guild))
                            return
                        }
                        val recommendation = spotifyApi.browse.getRecommendations(seedTracks = listOf(get.items[0].id), limit = 1).tracks[0]
                        println("${recommendation.name} by ${recommendation.artists[0].name}")
                        "${recommendation.name} by ${recommendation.artists[0].name}".load(guild.selfMember, channel ?: guild.defaultChannel)
                    } catch (ignored: Exception) {
                        ignored.printStackTrace()
                    }
                } else manager.nextTrack()
            }
        } catch (e: Exception) {
        }
    }

    private fun String.rmCharacters(characterSymbol: String): String {
        return when {
            characterSymbol.contains("[]") -> this.replace("\\s*\\[[^\\]]*\\]\\s*".toRegex(), " ")
            characterSymbol.contains("{}") -> this.replace("\\s*\\{[^\\}]*\\}\\s*".toRegex(), " ")
            else -> this.replace("\\s*\\([^\\)]*\\)\\s*".toRegex(), " ")
        }
    }

    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
        guild.musicManager(channel).scheduler.manager.nextTrack()
        channel?.send("${Emoji.BALLOT_BOX_WITH_CHECK} " + "The player got stuck... attempting to skip now now (this is Discord's fault) - If you encounter this multiple times, please type {0}leave".tr(channel!!.guild, guild.getPrefix()))
    }

    override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
        onException(exception)
    }


    private fun onException(exception: FriendlyException) {
        manager.current = null
        manager.nextTrack()
        try {
            manager.getChannel()?.sendMessage("I wasn't able to play that track, skipping... **Reason: **{0}".tr(manager.getChannel()!!.guild, exception.localizedMessage))?.queue()
        } catch (e: Exception) {
            e.log()
        }
    }
}

fun AudioTrack.getDurationFancy(): String {
    val length = info.length
    val seconds = (length / 1000).toInt()
    val minutes = seconds / 60
    val hours = minutes / 60
    return "[${String.format("%02d", hours % 60)}:${String.format("%02d", minutes % 60)}:${String.format("%02d", seconds % 60)}]"
}

fun AudioTrack.getCurrentTime(): String {
    val current = position
    val seconds = (current / 1000).toInt()
    val minutes = seconds / 60
    val hours = minutes / 60

    val length = info.length
    val lengthSeconds = (length / 1000).toInt()
    val lengthMinutes = lengthSeconds / 60
    val lengthHours = lengthMinutes / 60

    return "[${String.format("%02d", hours % 60)}:${String.format("%02d", minutes % 60)}:${String
            .format("%02d", seconds % 60)} / ${String.format("%02d", lengthHours % 60)}:${String
            .format("%02d", lengthMinutes % 60)}:${String.format("%02d", lengthSeconds % 60)}]"
}

@Synchronized
fun Guild.musicManager(channel: TextChannel?): GuildMusicManager {
    val guildId = id.toLong()
    var musicManager = managers[guildId]
    if (musicManager == null) {
        musicManager = GuildMusicManager(playerManager, channel, this)
        audioManager.sendingHandler = musicManager.sendHandler
        managers.put(guildId, musicManager)
    } else if (channel != null) musicManager.channel = channel
    return musicManager
}