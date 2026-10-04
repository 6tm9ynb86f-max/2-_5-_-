package com.example.a2_5_

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                PhotoGallery()
            }
        }
    }
}

// Каталог фотографий приложения.
private fun picturesDirectory(context: Context): File {
    val directory = context.getExternalFilesDir(
        Environment.DIRECTORY_PICTURES
    ) ?: throw IOException("Хранилище фотографий недоступно")

    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("Не удалось создать папку фотографий")
    }

    return directory
}

// Создаём файл с именем IMG_yyyyMMdd_HHmmss.jpg.
private fun createPhotoFile(context: Context): File {
    val directory = picturesDirectory(context)
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    var time = System.currentTimeMillis()

    while (true) {
        val file = File(
            directory,
            "IMG_${formatter.format(Date(time))}.jpg"
        )

        if (file.createNewFile()) return file

        // При совпадении имени не перезаписываем существующий файл.
        time += 1_000L
    }
}

// Сканируем папку при запуске и после добавления фотографии.
private fun readPhotos(context: Context): List<File> {
    val directory = picturesDirectory(context)

    val files = directory.listFiles()
        ?: throw IOException("Не удалось прочитать папку фотографий")

    val pattern = Regex("IMG_\\d{8}_\\d{6}\\.jpg")

    return files.filter {
        it.isFile && pattern.matches(it.name) && it.length() > 0L
    }.sortedByDescending {
        it.name
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoGallery() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var photos by remember {
        mutableStateOf<List<File>>(emptyList())
    }

    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var reloadVersion by remember { mutableIntStateOf(0) }

    var selectedPath by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var pendingPath by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(reloadVersion) {
        loading = true

        try {
            photos = withContext(Dispatchers.IO) {
                readPhotos(context)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            snackbar.showSnackbar(
                error.message ?: "Не удалось загрузить фотографии"
            )
        } finally {
            loading = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val path = pendingPath
        pendingPath = null
        preparing = true

        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val file = path?.let { File(it) }

                    val valid = success &&
                            file != null &&
                            file.length() > 0L

                    if (!valid &&
                        file != null &&
                        file.exists() &&
                        !file.delete()
                    ) {
                        throw IOException(
                            "Не удалось удалить незавершённое фото"
                        )
                    }

                    valid
                }

                if (saved) {
                    loading = true
                    reloadVersion++
                    snackbar.showSnackbar("Фото сохранено")
                } else if (success) {
                    snackbar.showSnackbar(
                        "Камера не сохранила фотографию"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                snackbar.showSnackbar(
                    error.message ?: "Ошибка сохранения фото"
                )
            } finally {
                preparing = false
            }
        }
    }

    fun launchCamera() {
        if (preparing || pendingPath != null || loading) return

        preparing = true

        scope.launch {
            var createdFile: File? = null

            try {
                val file = withContext(Dispatchers.IO) {
                    createPhotoFile(context)
                }

                createdFile = file

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                pendingPath = file.absolutePath
                cameraLauncher.launch(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pendingPath = null

                withContext(Dispatchers.IO) {
                    createdFile?.delete()
                }

                snackbar.showSnackbar(
                    error.message ?: "Не удалось открыть камеру"
                )
            } finally {
                preparing = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            scope.launch {
                snackbar.showSnackbar(
                    "Для съёмки разреши доступ к камере"
                )
            }
        }
    }

    val canTakePhoto = !loading &&
            !preparing &&
            pendingPath == null

    val takePhoto: () -> Unit = {
        if (canTakePhoto) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                permissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
        }
    }

    BackHandler(enabled = selectedPath != null) {
        selectedPath = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedPath == null) {
                            "Фотогалерея"
                        } else {
                            "Просмотр фото"
                        }
                    )
                },
                navigationIcon = {
                    if (selectedPath != null) {
                        TextButton(
                            onClick = { selectedPath = null }
                        ) {
                            Text("Назад")
                        }
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar)
        },
        floatingActionButton = {
            if (
                selectedPath == null &&
                photos.isNotEmpty() &&
                canTakePhoto
            ) {
                ExtendedFloatingActionButton(
                    onClick = takePhoto
                ) {
                    Text("Сделать фото")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val path = selectedPath

            when {
                path != null -> {
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Выбранная фотография",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                }

                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                photos.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "У вас пока нет фото",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Button(
                            onClick = takePhoto,
                            enabled = canTakePhoto
                        ) {
                            Text("Сделать первое фото")
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 96.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = photos,
                            key = { it.name }
                        ) { file ->
                            Card(
                                onClick = {
                                    selectedPath = file.absolutePath
                                }
                            ) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = "Фотография ${file.name}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                )
                            }
                        }
                    }
                }
            }

            if (preparing || pendingPath != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}