package com.nuvio.tv.core.livetv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

object LiveTvManager {
    private const val TAG = "LiveTvManager"
    const val BELL_PACKAGE = "com.quickplay.android.bellmediaplayer"

    private val _channels = MutableStateFlow<List<LiveTvChannel>>(emptyList())
    val channels: StateFlow<List<LiveTvChannel>> = _channels.asStateFlow()

    init {
        refreshChannels()
    }

    fun refreshChannels() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentMin = cal.get(Calendar.MINUTE)
        val slotStart = now - (currentMin % 30) * 60_000L
        val slotEnd = slotStart + 30 * 60_000L
        val nextSlotEnd = slotEnd + 60 * 60_000L

        val list = listOf(
            // Sports
            LiveTvChannel(
                id = "1400",
                number = "1400",
                name = "TSN 1 HD",
                callSign = "TSN1",
                category = LiveTvCategory.SPORTS,
                logoText = "TSN1",
                accentColorHex = "#C8102E",
                currentProgram = LiveProgram(
                    title = "SportsCentre Live",
                    episodeTitle = "Breaking NHL & NBA Highlights",
                    description = "Comprehensive Canadian sports news, live trade analysis, game recaps and nightly highlights from around the sporting world.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "Sports",
                    backdropUrl = "https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=1280&q=80"
                ),
                nextProgram = LiveProgram(
                    title = "NHL on TSN Live: Pre-Game",
                    description = "In-depth panel breakdown, starting lineups and live ice-level reports.",
                    startTimestampMs = slotEnd + 30 * 60_000L,
                    endTimestampMs = nextSlotEnd + 60 * 60_000L,
                    genre = "Hockey"
                )
            ),
            LiveTvChannel(
                id = "1405",
                number = "1405",
                name = "Sportsnet Ontario HD",
                callSign = "SNET",
                category = LiveTvCategory.SPORTS,
                logoText = "SN ONT",
                accentColorHex = "#0B2265",
                currentProgram = LiveProgram(
                    title = "Hockey Central at Noon",
                    episodeTitle = "Trade Deadline Preview",
                    description = "The hockey insider panel debates Stanley Cup favorites, team chemistry, and breaking lineup adjustments.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd,
                    genre = "Sports",
                    backdropUrl = "https://images.unsplash.com/photo-1580748141549-71748dbe0bdc?auto=format&fit=crop&w=1280&q=80"
                ),
                nextProgram = LiveProgram(
                    title = "Blue Jays in 30",
                    description = "Fast-paced, full-game condensed recap of all the crucial plays and home runs.",
                    startTimestampMs = slotEnd,
                    endTimestampMs = nextSlotEnd,
                    genre = "Baseball"
                )
            ),
            LiveTvChannel(
                id = "1409",
                number = "1409",
                name = "Sportsnet ONE",
                callSign = "SN ONE",
                category = LiveTvCategory.SPORTS,
                logoText = "SN ONE",
                accentColorHex = "#0B2265",
                currentProgram = LiveProgram(
                    title = "NBA Basketball Live",
                    episodeTitle = "Toronto Raptors vs. Boston Celtics",
                    description = "Live Eastern Conference showdown with courtside audio, live stats, and high-tempo action.",
                    startTimestampMs = slotStart - 20 * 60_000L,
                    endTimestampMs = slotStart + 100 * 60_000L,
                    genre = "Basketball",
                    backdropUrl = "https://images.unsplash.com/photo-1546519638-68e109498ffc?auto=format&fit=crop&w=1280&q=80"
                )
            ),
            LiveTvChannel(
                id = "1402",
                number = "1402",
                name = "TSN 3 HD",
                callSign = "TSN3",
                category = LiveTvCategory.SPORTS,
                logoText = "TSN3",
                accentColorHex = "#C8102E",
                currentProgram = LiveProgram(
                    title = "PGA Tour Live Coverage",
                    episodeTitle = "Third Round Spotlight",
                    description = "Signature hole coverage, driving stats and live leaderboard updates from the weekend championship.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 60 * 60_000L,
                    genre = "Golf"
                )
            ),

            // Entertainment & Drama
            LiveTvChannel(
                id = "1000",
                number = "1000",
                name = "CTV Toronto HD",
                callSign = "CTV",
                category = LiveTvCategory.ENTERTAINMENT,
                logoText = "CTV",
                accentColorHex = "#00539B",
                currentProgram = LiveProgram(
                    title = "The Tonight Show Live",
                    episodeTitle = "Special Guests & Live Music",
                    description = "Hilarious monologues, celebrity interviews, comedy sketches and a show-stopping musical performance.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "Entertainment",
                    backdropUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=1280&q=80"
                ),
                nextProgram = LiveProgram(
                    title = "CTV National News with Omar Sachedina",
                    description = "Canada's top stories, investigative reports and global updates.",
                    startTimestampMs = slotEnd + 30 * 60_000L,
                    endTimestampMs = nextSlotEnd + 30 * 60_000L,
                    genre = "News"
                )
            ),
            LiveTvChannel(
                id = "1003",
                number = "1003",
                name = "Global Toronto HD",
                callSign = "GLOBAL",
                category = LiveTvCategory.ENTERTAINMENT,
                logoText = "GLOBAL",
                accentColorHex = "#00A3E0",
                currentProgram = LiveProgram(
                    title = "Survivor Live",
                    episodeTitle = "Immunity Challenge Drama",
                    description = "Castaways compete in a brutal multi-stage physical challenge before a blindsiding Tribal Council vote.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "Reality",
                    backdropUrl = "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=1280&q=80"
                )
            ),
            LiveTvChannel(
                id = "1630",
                number = "1630",
                name = "Bravo / CTV Drama",
                callSign = "BRAVO",
                category = LiveTvCategory.ENTERTAINMENT,
                logoText = "BRAVO",
                accentColorHex = "#F47D20",
                currentProgram = LiveProgram(
                    title = "Below Deck Mediterranean",
                    episodeTitle = "High Drama on the High Seas",
                    description = "Interior and deck crew face luxury charter guest demands, galley meltdowns, and explosive dinner table arguments.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "High Drama",
                    backdropUrl = "https://images.unsplash.com/photo-1500917293891-ef795e70e1f6?auto=format&fit=crop&w=1280&q=80"
                )
            ),
            LiveTvChannel(
                id = "1600",
                number = "1600",
                name = "Showcase HD",
                callSign = "SHOWCASE",
                category = LiveTvCategory.ENTERTAINMENT,
                logoText = "SHOWCASE",
                accentColorHex = "#683294",
                currentProgram = LiveProgram(
                    title = "Poker Face",
                    episodeTitle = "The Orpheus Syndrome",
                    description = "Charlie Cale stumbles into an eccentric film studio with dark secrets and utilizes her human lie detector gift to crack the case.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "Mystery"
                )
            ),
            LiveTvChannel(
                id = "1610",
                number = "1610",
                name = "HGTV Canada",
                callSign = "HGTV",
                category = LiveTvCategory.ENTERTAINMENT,
                logoText = "HGTV",
                accentColorHex = "#00833E",
                currentProgram = LiveProgram(
                    title = "House Hunters International",
                    episodeTitle = "A Dream Villa in Tuscany",
                    description = "A couple tours stunning historic villas in Italy, balancing modern conveniences with classic Tuscan architecture.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd,
                    genre = "Lifestyle"
                )
            ),

            // Movies & HBO
            LiveTvChannel(
                id = "1250",
                number = "1250",
                name = "Crave 1 HD",
                callSign = "CRAVE1",
                category = LiveTvCategory.MOVIES,
                logoText = "CRAVE",
                accentColorHex = "#142562",
                currentProgram = LiveProgram(
                    title = "Dune: Part Two",
                    episodeTitle = "Feature Film (2024)",
                    description = "Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family.",
                    startTimestampMs = slotStart - 40 * 60_000L,
                    endTimestampMs = slotStart + 120 * 60_000L,
                    genre = "Sci-Fi Epic",
                    backdropUrl = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?auto=format&fit=crop&w=1280&q=80"
                )
            ),
            LiveTvChannel(
                id = "1254",
                number = "1254",
                name = "HBO Canada HD",
                callSign = "HBO",
                category = LiveTvCategory.MOVIES,
                logoText = "HBO",
                accentColorHex = "#1D1D1D",
                currentProgram = LiveProgram(
                    title = "The White Lotus",
                    episodeTitle = "Season 2 Finale: Arrivederci",
                    description = "Tensions bubble over among guests and staff on the final glamorous day in Sicily with unforgettable confrontations.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 35 * 60_000L,
                    genre = "Prestige Drama",
                    backdropUrl = "https://images.unsplash.com/photo-1533105079780-92b9be482077?auto=format&fit=crop&w=1280&q=80"
                )
            ),
            LiveTvChannel(
                id = "1255",
                number = "1255",
                name = "Starz 1 HD",
                callSign = "STARZ",
                category = LiveTvCategory.MOVIES,
                logoText = "STARZ",
                accentColorHex = "#000000",
                currentProgram = LiveProgram(
                    title = "John Wick: Chapter 4",
                    episodeTitle = "Feature Film",
                    description = "John Wick uncovers a path to defeating The High Table, facing deadly adversaries across Paris, Berlin, and Osaka.",
                    startTimestampMs = slotStart - 15 * 60_000L,
                    endTimestampMs = slotStart + 140 * 60_000L,
                    genre = "Action"
                )
            ),

            // News
            LiveTvChannel(
                id = "1501",
                number = "1501",
                name = "CP24 Live HD",
                callSign = "CP24",
                category = LiveTvCategory.NEWS,
                logoText = "CP24",
                accentColorHex = "#E31837",
                currentProgram = LiveProgram(
                    title = "CP24 Live at Noon",
                    episodeTitle = "Toronto Breaking News & Traffic",
                    description = "Live helicopter transit updates, local weather radar, civic politics, and instant breaking news coverage across the GTA.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd,
                    genre = "Live News"
                )
            ),
            LiveTvChannel(
                id = "1500",
                number = "1500",
                name = "CTV News Channel",
                callSign = "CTVNEWS",
                category = LiveTvCategory.NEWS,
                logoText = "CTV NEWS",
                accentColorHex = "#00539B",
                currentProgram = LiveProgram(
                    title = "Power & Politics",
                    episodeTitle = "National Panel Debate",
                    description = "Parliamentary insider debate, economic analysis, and breaking international headlines.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "Politics"
                )
            ),
            LiveTvChannel(
                id = "1505",
                number = "1505",
                name = "CNN International HD",
                callSign = "CNN",
                category = LiveTvCategory.NEWS,
                logoText = "CNN",
                accentColorHex = "#CC0000",
                currentProgram = LiveProgram(
                    title = "The Situation Room",
                    episodeTitle = "Live World News",
                    description = "Rapid response analysis, live field correspondents, and global political updates.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "World News"
                )
            ),

            // French / Français
            LiveTvChannel(
                id = "1880",
                number = "1880",
                name = "RDS HD (Réseau des sports)",
                callSign = "RDS",
                category = LiveTvCategory.FRENCH,
                logoText = "RDS",
                accentColorHex = "#E31B23",
                currentProgram = LiveProgram(
                    title = "Le 5 à 7 en direct",
                    episodeTitle = "Canadiens de Montréal & LNH",
                    description = "Toute l'actualité des Canadiens de Montréal, analyses exclusives, et entrevues d'avant-match.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 60 * 60_000L,
                    genre = "Sports",
                    backdropUrl = "https://images.unsplash.com/photo-1515523110800-9415d13b84a8?auto=format&fit=crop&w=1280&q=80"
                )
            ),
            LiveTvChannel(
                id = "1800",
                number = "1800",
                name = "ICI Radio-Canada Télé",
                callSign = "SRC",
                category = LiveTvCategory.FRENCH,
                logoText = "ICI TÉLÉ",
                accentColorHex = "#00853F",
                currentProgram = LiveProgram(
                    title = "Le Téléjournal en direct",
                    episodeTitle = "Grandes nouvelles du Québec et du monde",
                    description = "Les reportages d'enquête, l'économie canadienne et les événements marquants de la journée.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd + 30 * 60_000L,
                    genre = "Nouvelles"
                )
            ),
            LiveTvChannel(
                id = "1802",
                number = "1802",
                name = "TVA Montréal HD",
                callSign = "TVA",
                category = LiveTvCategory.FRENCH,
                logoText = "TVA",
                accentColorHex = "#0047BA",
                currentProgram = LiveProgram(
                    title = "Tricheur",
                    episodeTitle = "Émission spéciale avec célébrités",
                    description = "Jeu télévisé dynamique animé avec humour, surprises et compétition amicale.",
                    startTimestampMs = slotStart,
                    endTimestampMs = slotEnd,
                    genre = "Variétés"
                )
            )
        )

        _channels.value = list
    }

    fun tuneToChannel(context: Context, channel: LiveTvChannel) {
        Log.i(TAG, "Tuning to Bell Fibe channel: ${channel.number} (${channel.name})")

        // 1. Attempt official Bell Fibe deep-link scheme discovered via ADB
        val deepLinkUri = Uri.parse("fonsetv://channel/${channel.number}")
        val intent = Intent(Intent.ACTION_VIEW, deepLinkUri).apply {
            setPackage(BELL_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        try {
            context.startActivity(intent)
            Toast.makeText(context, "Tuning Bell Fibe: ${channel.name} (${channel.number})", Toast.LENGTH_SHORT).show()
            return
        } catch (e: Exception) {
            Log.w(TAG, "Direct deep-link failed, falling back to launch intent: ${e.message}")
        }

        // 2. Fallback: Launch Bell Fibe main leanback launcher with channel extras
        val launchIntent = context.packageManager.getLaunchIntentForPackage(BELL_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            launchIntent.putExtra("channel_id", channel.id)
            launchIntent.putExtra("channel_number", channel.number)
            try {
                context.startActivity(launchIntent)
                Toast.makeText(context, "Opening Bell Fibe TV: Channel ${channel.number}", Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch Bell Fibe app: ${e2.message}")
                Toast.makeText(context, "Could not open Bell Fibe TV app", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Bell Fibe TV app is not installed on this device", Toast.LENGTH_LONG).show()
        }
    }
}
