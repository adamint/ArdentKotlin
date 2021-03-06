package main

import Web
import com.adamratzman.spotify.SpotifyApi
import com.adamratzman.spotify.SpotifyAppApi
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.youtube.YouTube
import com.rethinkdb.RethinkDB
import com.rethinkdb.net.Connection
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager
import commands.`fun`.EightBall
import commands.`fun`.FML
import commands.`fun`.IsStreaming
import commands.`fun`.Meme
import commands.`fun`.UnixFortune
import commands.`fun`.UrbanDictionary
import commands.administrate.AdministrativeDaemon
import commands.administrate.AdministratorCommand
import commands.administrate.Automessages
import commands.administrate.Blacklist
import commands.administrate.Clear
import commands.administrate.GiveRoleToAll
import commands.info.About
import commands.info.Donate
import commands.info.GetId
import commands.info.Help
import commands.info.IamCommand
import commands.info.IamnotCommand
import commands.info.Invite
import commands.info.Ping
import commands.info.RoleInfo
import commands.info.ServerInfo
import commands.info.Status
import commands.info.Support
import commands.info.UserInfo
import commands.info.WebsiteCommand
import commands.music.ClearQueue
import commands.music.GoTo
import commands.music.GuildMusicManager
import commands.music.MyMusicLibrary
import commands.music.Pause
import commands.music.Play
import commands.music.Playing
import commands.music.Playlist
import commands.music.Queue
import commands.music.RemoveFrom
import commands.music.Repeat
import commands.music.Resume
import commands.music.Skip
import commands.music.SongUrl
import commands.music.Volume
import commands.music.connect
import commands.music.getAudioManager
import commands.music.load
import commands.music.play
import commands.rpg.Balance
import commands.rpg.Daily
import commands.rpg.ProfileCommand
import commands.rpg.TopMoney
import commands.settings.Prefix
import commands.settings.Settings
import events.CommandFactory
import events.JoinRemoveEvents
import events.VoiceUtils
import loginRedirect
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.hooks.AnnotatedEventManager
import net.dv8tion.jda.api.requests.GatewayIntent
import okhttp3.OkHttpClient
import org.apache.commons.io.IOUtils
import translation.LanguageCommand
import translation.Translate
import translation.tr
import utils.discord.getGuildById
import utils.discord.getTextChannelById
import utils.discord.getVoiceChannelById
import utils.discord.send
import utils.functionality.EventWaiter
import utils.functionality.logChannel
import utils.functionality.queryAsArrayList
import utils.music.LocalTrackObj
import utils.music.ServerQueue
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

var test by Delegates.notNull<Boolean>()
var beta = true

var hangout: Guild? = null

var r: RethinkDB = RethinkDB.r
var conn: Connection? = null

lateinit var config: Config

var jdas = mutableListOf<JDA>()
lateinit var waiter: EventWaiter
lateinit var factory: CommandFactory

val playerManager = DefaultAudioPlayerManager()
val managers = ConcurrentHashMap<Long, GuildMusicManager>()

lateinit var spotifyApi: SpotifyAppApi
lateinit var hostname: String

var transport: HttpTransport = GoogleNetHttpTransport.newTrustedTransport()
var jsonFactory: JacksonFactory = JacksonFactory.getDefaultInstance()

val sheets: Sheets = setupDrive()
val youtube: YouTube = setupYoutube()

val shards = 1

val httpClient = OkHttpClient()

fun main(args: Array<String>) {
    config = Config(args[0])
    test = config.getValue("test").toBoolean()
    hostname = if (test) "http://localhost" else "https://ardentbot.com"
    loginRedirect = "$hostname/api/oauth/login"

    /* val spreadsheet = sheets.spreadsheets().values().get("1qm27kGVQ4BdYjvPSlF0zM64j7nkW4HXzALFNcan4fbs", "A2:D").setKey(config.getValue("google"))
             .execute()
     spreadsheet.getValues().forEach { if (it.getOrNull(1) != null && it.getOrNull(2) != null) questions.add(TriviaQuestion(it[1] as String, (it[2] as String).split("~"), it[0] as String, (it.getOrNull(3) as String?)?.toIntOrNull() ?: 125)) }
     */Web()

    waiter = EventWaiter()
    factory = CommandFactory()
    spotifyApi = SpotifyApi.spotifyAppApi("79d455af5aea45c094c5cea04d167ac1", config.getValue("spotifySecret")).build()
    (1..shards).forEach { sh ->
        jdas.add(JDABuilder.create(config.getValue("token"), GatewayIntent.values().toList())
                .setActivity(Activity.playing("Play music with Ardent. /play"))
                .addEventListeners(waiter, factory, JoinRemoveEvents(), VoiceUtils())
                .setEventManager(AnnotatedEventManager())
                //  .useSharding(sh - 1, shards)
                .setToken(config.getValue("token"))
                .build())
    }

    logChannel = getTextChannelById("351368131639246848")
    hangout = getGuildById("351220166018727936")

    playerManager.configuration.resamplingQuality = AudioConfiguration.ResamplingQuality.LOW
    playerManager.registerSourceManager(YoutubeAudioSourceManager())
    playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault())
    playerManager.registerSourceManager(HttpAudioSourceManager())
    AudioSourceManagers.registerRemoteSources(playerManager)
    AudioSourceManagers.registerLocalSource(playerManager)

    val administrativeDaemon = AdministrativeDaemon()
//    administrativeExecutor.scheduleAtFixedRate(administrativeDaemon, 15, 30, TimeUnit.SECONDS)

    addCommands()

    waiter.executor.schedule({ checkQueueBackups() }, 45, TimeUnit.SECONDS)
    waiter.executor.scheduleWithFixedDelay({}, 30, 30, TimeUnit.SECONDS)
}

/**
 * Config class represents a text file with the following syntax on each line: KEY :: VALUE
 */
data class Config(val url: String) {
    private val keys: MutableMap<String, String> = hashMapOf()

    init {
        try {
            val keysTemp = IOUtils.readLines(FileReader(File(url)))
            keysTemp.forEach { pair ->
                val keyPair = pair.split(" :: ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (keyPair.size == 2) keys.put(keyPair[0], keyPair[1])
            }
            conn = r.connection().db("ardent_v2").hostname("localhost").connect()
            insertDbScaffold()
        } catch (e: IOException) {
            println("Unable to load Config, exiting now")
            e.printStackTrace()
            System.exit(1)
        }
    }

    fun getValue(keyName: String): String {
        return (keys as Map<String, String>).getOrDefault(keyName, "not_available")
    }
}

fun insertDbScaffold() {
    if (!r.dbList().run<List<String>>(conn).contains("ardent_v2")) r.dbCreate("ardent_v2").run<Any>(conn)
    val tables = listOf("savedQueues", "staff", "patrons", "musicPlaylists", "musicLibraries", "users", "phrases", "guilds", "music", "derogatoryTerms")
    tables.forEach { table ->
        if (!r.db("ardent_v2").tableList().run<List<String>>(conn).contains(table)) r.db("ardent_v2").tableCreate(table).run<Any>(conn)
    }
}

fun addCommands() {
    factory.addCommands(Ping(), Help(),
            Invite(), About(), Donate(), UserInfo(), ServerInfo(), RoleInfo(),
            UrbanDictionary(), UnixFortune(), EightBall(), FML(), Translate(), IsStreaming(), Status(), Clear(), Automessages(),
            AdministratorCommand(), GiveRoleToAll(), WebsiteCommand(), GetId(), Support(), /* IamCommand(), IamnotCommand(), */
            LanguageCommand(), Blacklist(), Meme(), IamnotCommand(), IamCommand())

    // Game Helper Commands
    // factory.addCommands(Decline(), InviteToGame(), Gamelist(), LeaveGame(), JoinGame(), Cancel(), Forcestart(), AcceptInvitation())

    // Statistics Commands
    /*factory.addCommands(CommandDistribution(), ServerLanguagesDistribution(), MusicInfo(), AudioAnalysisCommand(), GetGuilds(),  ShardInfo(), CalculateCommand(),
            MutualGuilds())*/

    // Settings Commands
    factory.addCommands(Prefix(), Settings())

    // Music Commands
    factory.addCommands(Playlist(), MyMusicLibrary(), Play(), Skip(), Pause(), Resume(), SongUrl(), Playing(), Queue())
    factory.addCommands(ClearQueue(), RemoveFrom(), Volume(), GoTo(), Repeat())
    /* factory.addCommands(Play(), Radio(), Stop(), Pause(), Resume(), SongUrl(), Volume(), Playing(), Repeat(),
            Shuffle(), Queue(), RemoveFrom(), Skip(), Prefix(), Leave(), ClearQueue(), RemoveAt(), ArtistSearch(), FastForward(),
            Rewind()) */

    // Game Commands
    // factory.addCommands(BlackjackCommand(), Connect4Command(), BetCommand(), TriviaCommand(), TicTacToeCommand())

    // RPG Commands
    factory.addCommands(TopMoney(), ProfileCommand(), Daily(), Balance())
}

fun checkQueueBackups() {
    val queues = r.table("savedQueues").run<Any>(conn).queryAsArrayList(ServerQueue::class.java)
    queues.forEach { queue ->
        if (queue == null || queue.tracks.isEmpty()) return
        val channel = getVoiceChannelById(queue.voiceId) ?: return
        val textChannel = getTextChannelById(queue.channelId) ?: return
        if (channel.members.size > 1 || (channel.members.size == 1 && channel.members[0] == channel.guild.selfMember)) {
            val manager = channel.guild.getAudioManager(textChannel)
            if (manager.channel != null) {
                if (channel.guild.selfMember.voiceState?.channel != channel) channel.connect(textChannel)
                textChannel.send(("**Restarting playback...**... Check out {0} for other cool features we offer in Ardent **Premium**").tr(channel.guild, "<$hostname/premium>"))
                queue.tracks.forEach { trackUrl ->
                    trackUrl.load(channel.guild.selfMember, textChannel, { audioTrack, id ->
                        play(manager.channel, channel.guild.selfMember, LocalTrackObj(channel.guild.selfMember.user.id, channel.guild.selfMember.user.id, null, null, null, id, audioTrack))
                    })
                }
                logChannel?.send("Resumed playback in `${channel.guild.name}` - channel `${channel.name}`")
            }
        }
    }
    r.table("savedQueues").delete().runNoReply(conn)
}

fun setupDrive(): Sheets {
    val builder = Sheets.Builder(transport, jsonFactory, null)
    builder.applicationName = "Ardent"
    return builder.build()
}

fun setupYoutube(): YouTube {
    return YouTube.Builder(transport, jsonFactory, null).setApplicationName("Ardent").build()
}
