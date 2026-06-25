package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.request.RequestOptions
import java.io.File

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun KioskImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    // Convert string paths to File objects if needed
    val processedModel = when (model) {
        is String -> {
            val file = File(model)
            println("DEBUG: KioskImage - Processing string model: $model, file exists: ${file.exists()}, size: ${file.length()}")
            if (file.exists()) file else model
        }
        is File -> {
            println("DEBUG: KioskImage - Processing File model: ${model.absolutePath}, exists: ${model.exists()}, size: ${model.length()}")
            model
        }
        else -> {
            println("DEBUG: KioskImage - Processing model of type: ${model?.javaClass?.simpleName}")
            model
        }
    }
    
    // Determine if this is a valid local file
    val isValidFile = processedModel is File && processedModel.exists() && processedModel.length() > 0
    val shouldShowPlaceholder = processedModel == null || 
                                (processedModel is File && !processedModel.exists()) ||
                                (processedModel is String && !File(processedModel).exists())
    
    println("DEBUG: KioskImage - shouldShowPlaceholder: $shouldShowPlaceholder, isValidFile: $isValidFile")
    
    Box(
        modifier = modifier.background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (shouldShowPlaceholder) {
            // Show placeholder for missing images
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.example.playerapp.R.drawable.well2day_logo),
                    contentDescription = "Default Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            GlideImage(
                model = processedModel,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentScale = contentScale,
                requestBuilderTransform = { 
                    it.diskCacheStrategy(DiskCacheStrategy.NONE)
                      .skipMemoryCache(true)
                }
            )
        }
    }
}

