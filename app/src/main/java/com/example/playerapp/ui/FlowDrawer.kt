package com.example.playerapp.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.material3.Text
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import com.example.playerapp.data.Repository
import java.io.File
import kotlinx.coroutines.delay
import com.example.playerapp.utils.InfoStorage
import org.json.JSONObject

data class FlowDrawerAssets(
    val topAssets: List<String> = emptyList(),
    val bottomAssets: List<String> = emptyList()
)

fun FlowDrawerAssets.hasRenderableAssets(): Boolean =
    topAssets.any { it.isNotBlank() } || bottomAssets.any { it.isNotBlank() }

// ─── UI constants ─────────────────────────────────────────────────────────────

private val DrawerBg      = Color.Black.copy(alpha = 0.4f)
private const val SlotCorner    = 12
private const val SlotSpacing   = 10
private const val DrawerPadding = 12

// ─── Main drawer ─────────────────────────────────────────────────────────────

@Composable
fun FlowDrawer(
    isVisible: Boolean,
    assets: FlowDrawerAssets,
    modifier: Modifier = Modifier,
    drawerWidth: Float = 260f,
    onAnimationEnd: (() -> Unit)? = null
) {
    val context = LocalContext.current
    
    // Debug logging for FlowDrawer state
    println("=== FLOW DRAWER DEBUG ===")
    println("isVisible: $isVisible")
    println("topAssets count: ${assets.topAssets.size}")
    println("bottomAssets count: ${assets.bottomAssets.size}")
    println("topAssets: ${assets.topAssets}")
    println("bottomAssets: ${assets.bottomAssets}")
    println("hasRenderableAssets: ${assets.hasRenderableAssets()}")
    println("========================")

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {

        AnimatedVisibility(
            visible = isVisible,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            label = "drawerVisibility"
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth.dp)
                    .background(
                        color = DrawerBg,
                        shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
                    )
                    .zIndex(9999f)
            ) {
                DrawerSlotColumn(assets = assets, context = context)
            }
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(400)
            onAnimationEnd?.invoke()
        }
    }
}


// ─── Slot column ─────────────────────────────────────────────────────────────

@Composable
private fun DrawerSlotColumn(assets: FlowDrawerAssets, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(DrawerPadding.dp),
        verticalArrangement = Arrangement.spacedBy(SlotSpacing.dp)
    ) {
        // Display top assets
        assets.topAssets.forEachIndexed { index, imageUrl ->
            SlotItem(
                imageUrl = imageUrl,
                context = context,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
        
        // Display bottom assets
        assets.bottomAssets.forEachIndexed { index, imageUrl ->
            SlotItem(
                imageUrl = imageUrl,
                context = context,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

// ─── Single slot ─────────────────────────────────────────────────────────────

@Composable
private fun SlotItem(
    imageUrl: String,
    context: Context,
    modifier: Modifier = Modifier
) {
    println("DEBUG: SlotItem - imageUrl: $imageUrl")
    
    if (imageUrl.isNotBlank()) {
        println("DEBUG: SlotItem - Rendering image: $imageUrl")
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SlotCorner.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            KioskImage(
                model = imageUrl,
                contentDescription = "Flow Drawer Image",
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentScale = ContentScale.Fit
            )
        }
    } else {
        println("DEBUG: SlotItem - Rendering empty slot")
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SlotCorner.dp))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            // Empty slot
        }
    }
}

// ─── Legacy public composables (unchanged call-site signatures) ───────────────

@Composable
fun FlowDrawerAssetSection(
    assets: List<String>,
    context: Context,
    totalSlotCount: Int,
    isTopSection: Boolean = true,
    modifier: Modifier = Modifier
) {
    val expectedSlots = if (isTopSection) (totalSlotCount + 1) / 2 else totalSlotCount / 2
    val slotsToRender = minOf(assets.size, expectedSlots)

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SlotSpacing.dp)
    ) {
        repeat(slotsToRender) { index ->
            SlotItem(
                imageUrl = assets[index],
                context = context,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        repeat(expectedSlots - slotsToRender) { extra ->
            SlotItem(
                imageUrl = "",
                context = context,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
fun FlowDrawerAssetItem(
    imageUrl: String,
    context: Context,
    modifier: Modifier = Modifier
) = SlotItem(imageUrl = imageUrl, context = context, modifier = modifier)

// ─── Asset loading (logic unchanged) ─────────────────────────────────────────

@Composable
fun rememberFlowDrawerAssets(context: Context, screenId: String? = null): FlowDrawerAssets =
    remember(context, screenId) { loadFlowDrawerAssets(context, screenId) }

fun loadFlowDrawerAssets(context: Context, screenId: String? = null): FlowDrawerAssets {
    // Try to load cached assets from local storage
    val flowDrawerDir = if (screenId != null) {
        File(context.filesDir, "flow_drawer_images_$screenId")
    } else {
        null
    }
    val configuredSlotCount = InfoStorage.getFlowDrawerSlotCount(context)?.coerceAtLeast(1) ?: 2
    
    if (flowDrawerDir?.exists() == true) {
        val imagePaths = MutableList(configuredSlotCount) { "" }
        flowDrawerDir.listFiles()
            ?.filter { it.exists() && it.length() > 0 && it.name.startsWith("slot_") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val slotNumber = Regex("""^slot_(\d+)""").find(file.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                if (slotNumber != null && slotNumber in 1..configuredSlotCount) {
                    imagePaths[slotNumber - 1] = file.absolutePath
                }
            }
            
        println(
            "DEBUG: FlowDrawer.loadFlowDrawerAssets - Loaded " +
                "${imagePaths.count { it.isNotBlank() }}/$configuredSlotCount cached images for screen: $screenId"
        )
        return FlowDrawerAssets(topAssets = imagePaths, bottomAssets = emptyList())
    }
    
    println("DEBUG: FlowDrawer.loadFlowDrawerAssets - No cached images found for screen: $screenId")
    return FlowDrawerAssets(topAssets = List(configuredSlotCount) { "" }, bottomAssets = emptyList())
}

fun refreshFlowDrawerAssets(
    context: Context,
    screenId: String,
    onResult: (Boolean, FlowDrawerAssets, String?) -> Unit
) {
    Repository.getFlowDrawerImages(context, screenId) { success, imagePaths, error ->
        // imagePaths now contains local file paths, not URLs
        val loadedAssets = FlowDrawerAssets(topAssets = imagePaths ?: emptyList(), bottomAssets = emptyList())
        onResult(success, loadedAssets, error)
    }
}

// ─── Refresh wrapper ──────────────────────────────────────────────────────────

@Composable
fun FlowDrawerWithRefresh(
    isVisible: Boolean,
    context: Context,
    refreshTrigger: Int,
    screenId: String? = null,
    modifier: Modifier = Modifier,
    drawerWidth: Float = 260f,
    onAnimationEnd: (() -> Unit)? = null
) {
    val assets by remember(refreshTrigger, screenId) {
        mutableStateOf(loadFlowDrawerAssets(context, screenId))
    }
    FlowDrawer(
        isVisible = isVisible,
        assets = assets,
        modifier = modifier,
        drawerWidth = drawerWidth,
        onAnimationEnd = onAnimationEnd
    )
}

// ─── Debug ────────────────────────────────────────────────────────────────────

fun debugFlowDrawerConfig(context: Context, screenId: String? = null) {
    println("=== FLOW DRAWER DEBUG | screen=$screenId ===")
    println("Simplified structure - using image URLs from server")
    println("=== END ===")
}
