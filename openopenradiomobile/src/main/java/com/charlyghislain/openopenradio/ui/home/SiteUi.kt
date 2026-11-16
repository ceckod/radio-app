package com.charlyghislain.openopenradio.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import com.charlyghislain.openopenradio.ui.components.BinaryHeartRating
import com.charlyghislain.openopenradio.ui.components.PlayPauseIcon
import com.charlyghislain.openopenradio.ui.components.RadioControllerViewModel
import com.charlyghislain.openopenradio.ui.components.awaitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// ===== Визия от сайта =====
private val SiteBg = Color(0xFF090D16)
private val SiteCard = Color(0xFF16233F)
private val SiteLine = Color(0xFF2A3C66)
private val SiteAccent = Color(0xFF2F9BFF)
private val SiteMuted = Color(0xFF8D9AB0)
private val SiteText = Color(0xFFF2F5FA)

@Composable
fun SiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SiteAccent,
            background = SiteBg,
            surface = SiteCard,
            onSurface = SiteText,
            onBackground = SiteText,
        ),
        content = content
    )
}

// ===== Стилове (същите като в сайта) =====
// Името на жанра от webradiodb се сравнява с ключовите думи; първото съвпадение печели.
private val STYLES: List<Pair<String, List<String>>> = listOf(
    "Хип-хоп" to listOf("hip hop", "hiphop", "hip-hop", "rap", "trap"),
    "R&B и соул" to listOf("r&b", "rnb", "soul", "motown", "funk"),
    "Метъл" to listOf("metal"),
    "Рок" to listOf("rock", "alternative", "indie", "punk"),
    "Фолк и чалга" to listOf("folk", "chalga", "balkan", "popfolk", "pop-folk", "pop folk"),
    "Новини и говор" to listOf("news", "talk", "sport"),
    "Джаз" to listOf("jazz", "blues", "swing"),
    "Класика" to listOf("classical", "opera", "symphony"),
    "Чил и лаунж" to listOf("chill", "chillout", "lounge", "ambient", "relax", "downtempo"),
    "Денс и електронна" to listOf("dance", "electro", "electronic", "house", "edm", "techno", "trance", "club", "dnb"),
    "Реге" to listOf("reggae", "ska", "dub", "dancehall"),
    "Латино" to listOf("latin", "salsa", "reggaeton", "bachata", "latino", "spanish"),
    "Кънтри" to listOf("country", "bluegrass"),
    "Ретро" to listOf("oldies", "retro", "80s", "90s", "70s", "60s", "disco"),
    "Поп и хитове" to listOf("pop", "hits", "top40", "top 40", "top", "charts"),
)

private val STYLE_REGEXES: List<Pair<String, Regex>> = STYLES.map { (name, words) ->
    name to Regex(
        "(?:^|[^a-zа-я0-9])(?:" + words.joinToString("|") { Regex.escape(it) } + ")(?![a-zа-я0-9])",
        RegexOption.IGNORE_CASE
    )
}

private fun styleOf(genre: String): String? =
    STYLE_REGEXES.firstOrNull { (_, re) -> re.containsMatchIn(genre) }?.first

// ===== Зареждане през MediaBrowser (същото дърво, което ползва и Android Auto) =====
private suspend fun MediaBrowser.children(
    parentId: String,
    pageSize: Int = 500,
    maxPages: Int = 40
): List<MediaItem> {
    val out = mutableListOf<MediaItem>()
    var page = 0
    while (page < maxPages) {
        val items = getChildren(parentId, page, pageSize, null).awaitResult().value ?: break
        out.addAll(items)
        if (items.size < pageSize) break
        page++
    }
    return out
}

private suspend fun loadRadio(
    b: MediaBrowser,
    style: String?,
    worldwide: Boolean,
    bgIds: Set<String>,
    genres: List<MediaItem>
): List<MediaItem> {
    if (style == null) {
        if (worldwide) return b.children("[all]", pageSize = 300, maxPages = 1)
        val bg = b.children("[country]:Bulgaria")
        return if (bg.isNotEmpty()) bg else b.children("[all]", pageSize = 300, maxPages = 1)
    }
    val matching = genres.filter { styleOf(it.mediaMetadata.title?.toString() ?: "") == style }
    val merged = LinkedHashMap<String, MediaItem>()
    for (g in matching.take(25)) {
        for (s in b.children(g.mediaId)) merged.putIfAbsent(s.mediaId, s)
    }
    var list = merged.values.toList()
    if (!worldwide && bgIds.isNotEmpty()) list = list.filter { it.mediaId in bgIds }
    return list
        .sortedBy { it.mediaMetadata.title?.toString()?.lowercase() ?: "" }
        .take(400)
}

// ===== Екран =====
@Composable
fun SiteHome(viewModel: RadioControllerViewModel, browser: MediaBrowser?) {
    val context = LocalContext.current
    val controller by viewModel.controller.collectAsState()
    val current by viewModel.mediaItemFlow.collectAsState()
    val playing by viewModel.mediaPlayingFlow.collectAsState()
    val song by viewModel.songFlow.collectAsState()

    var tab by remember { mutableStateOf(0) } // 0 радио, 1 любими, 2 търсене
    var style by remember { mutableStateOf<String?>(null) }
    var worldwide by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var stations by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var favTick by remember { mutableStateOf(0) }
    var bgIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var genreItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(viewModel) { viewModel.reloadEventFlow.collect { favTick++ } }

    LaunchedEffect(browser) {
        val b = browser ?: return@LaunchedEffect
        try {
            bgIds = b.children("[country]:Bulgaria").map { it.mediaId }.toSet()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        try {
            genreItems = b.children("[genre]")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    LaunchedEffect(browser, tab, style, worldwide, query, favTick, bgIds, genreItems) {
        val b = browser ?: return@LaunchedEffect
        loading = true
        try {
            stations = when (tab) {
                1 -> b.children("[favorites]")
                2 -> {
                    val q = query.trim()
                    if (q.length < 2) {
                        emptyList()
                    } else {
                        delay(400)
                        b.search(q, null).awaitResult()
                        b.getSearchResult(q, 0, 200, null).awaitResult().value?.toList()
                            ?: emptyList()
                    }
                }
                else -> loadRadio(b, style, worldwide, bgIds, genreItems)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            stations = emptyList()
        }
        loading = false
    }

    val playItem: (MediaItem) -> Unit = { item ->
        controller?.let { c ->
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }
    val isFav = (current?.mediaMetadata?.userRating as? HeartRating)?.isHeart ?: false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SiteBg)
    ) {
        Hero(
            item = current,
            song = song,
            playing = playing,
            isFav = isFav,
            onPlayPause = { viewModel.onPlayPause() },
            onFav = { viewModel.onSetRating(!isFav) },
            onWho = {
                val msg = song?.let { "🎵 $it" }
                    ?: "Радиото не подава име на песента в момента."
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip("Радио", tab == 0) { tab = 0 }
            Chip("★ Любими", tab == 1) { tab = 1 }
            Chip("Търсене", tab == 2) { tab = 2 }
        }

        if (tab == 0) {
            LazyRow(
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Chip("Всички", style == null) { style = null } }
                items(STYLES) { (name, _) ->
                    Chip(name, style == name) { style = name }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chip("🇧🇬 България", !worldwide) { worldwide = false }
                Chip("🌍 Светът", worldwide) { worldwide = true }
            }
        }

        if (tab == 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SiteCard)
                    .border(1.dp, SiteLine, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (query.isEmpty()) {
                    Text("Търси радио по име или жанр…", color = SiteMuted, fontSize = 16.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = SiteText, fontSize = 16.sp),
                    cursorBrush = SolidColor(SiteAccent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        when {
            browser == null -> Message("Свързвам…")
            loading && stations.isEmpty() -> Message("Зареждам…")
            stations.isEmpty() -> Message(
                when (tab) {
                    1 -> "Още нямаш любими – пусни радио и натисни ♡"
                    2 -> "Въведи поне 2 букви"
                    else -> "Няма радиа в този стил. Опитай „🌍 Светът“."
                }
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
            ) {
                items(stations, key = { it.mediaId }) { item ->
                    StationCard(
                        item = item,
                        playing = item.mediaId == current?.mediaId,
                        onClick = { playItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(
    item: MediaItem?,
    song: String?,
    playing: Boolean,
    isFav: Boolean,
    onPlayPause: () -> Unit,
    onFav: () -> Unit,
    onWho: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0E1430), Color(0xFF3B2A8C))))
            .border(1.dp, Color(0xFF243060), shape)
            .padding(16.dp)
    ) {
        Text(
            text = if (playing) "● СЕГА СВИРИ" else if (item != null) "НА ПАУЗА" else "РАДИО",
            color = SiteAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = item?.mediaMetadata?.title?.toString() ?: "Избери радио",
            color = SiteText,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (song != null) {
            Text(
                text = "🎵 $song",
                color = SiteAccent,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseIcon(isPlaying = playing, color = SiteText, onPlayPauseClick = onPlayPause)
            BinaryHeartRating(isFavorite = isFav, onToggleFavorite = onFav, color = Color(0xFFEF4444))
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onWho,
                colors = ButtonDefaults.buttonColors(containerColor = SiteCard, contentColor = SiteText)
            ) {
                Text("🎵 Коя е песента?")
            }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (on) SiteAccent else SiteCard)
            .border(1.dp, if (on) SiteAccent else SiteLine, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (on) Color(0xFF04101F) else Color(0xFFC3CBDA),
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun StationCard(item: MediaItem, playing: Boolean, onClick: () -> Unit) {
    val name = item.mediaMetadata.title?.toString() ?: ""
    val hue = ((name.sumOf { it.code } * 37) % 360).toFloat()
    val accent = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.85f)))
    val tint = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.5f)))
    val shape = RoundedCornerShape(18.dp)
    val fav = (item.mediaMetadata.userRating as? HeartRating)?.isHeart ?: false
    Box(
        modifier = Modifier
            .padding(4.dp)
            .height(96.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(SiteCard, tint.copy(alpha = 0.45f))))
            .border(if (playing) 2.dp else 1.dp, if (playing) accent else SiteLine, shape)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = name,
                color = SiteText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = (if (fav) "♥ " else "") + (item.mediaMetadata.genre?.toString() ?: "Радио"),
                color = SiteMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = SiteMuted, fontSize = 16.sp)
    }
}
