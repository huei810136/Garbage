package tw.edu.pu.csim.s1113426.garbage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.vision.detector.Detection

// ====== 垃圾分類表 ======
val recycleItems = setOf(
    "bottle", "wine glass", "cup", "bowl", "book",
    "spoon", "fork", "knife", "laptop", "mouse",
    "keyboard", "cell phone", "tv", "remote", "microwave",
    "oven", "toaster", "refrigerator", "scissors", "toothbrush"
)

val trashItems = setOf(
    "banana", "apple", "sandwich", "orange", "broccoli",
    "carrot", "hot dog", "pizza", "donut", "cake", "teddy bear"
)

fun classifyItem(itemName: String): String {
    return when {
        recycleItems.contains(itemName) -> "回收"
        trashItems.contains(itemName) -> "一般垃圾"
        else -> "其他"
    }
}

// ====== ImageProxy → Bitmap ======
fun ImageProxy.toBitmap(context: Context): Bitmap {
    val converter = YuvToRgbConverter(context)
    return converter.yuvToRgb(this)
}

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val localContext = LocalContext.current

            var detectedItem by remember { mutableStateOf("尚未偵測") }
            var category by remember { mutableStateOf("未知") }
            var confidence by remember { mutableStateOf(0f) }

            // ===== 動態請求相機權限 =====
            val cameraPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted ->
                    if (!granted) {
                        detectedItem = "無法取得相機權限"
                        category = "請允許權限"
                    }
                }
            )

            LaunchedEffect(Unit) {
                if (ContextCompat.checkSelfPermission(
                        localContext,
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            // ===== TensorFlow Lite ObjectDetector (簡化版本) =====
            val objectDetector by remember {
                mutableStateOf(
                    try {
                        // 使用最基本的方法創建檢測器
                        ObjectDetector.createFromFile(localContext, "efficientdet_lite0.tflite")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                )
            }

            Scaffold(
                topBar = { CenterAlignedTopAppBar(title = { Text("垃圾分類 Demo") }) }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // ===== CameraX Preview =====
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    500
                                )

                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()

                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(surfaceProvider)
                                    }

                                    val imageAnalyzer = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                        .also { analyzer ->
                                            analyzer.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                                try {
                                                    objectDetector?.let { detector ->
                                                        val bitmap = imageProxy.toBitmap(ctx)
                                                        val tensorImage = TensorImage.fromBitmap(bitmap)
                                                        val results: List<Detection> = detector.detect(tensorImage)

                                                        if (results.isNotEmpty()) {
                                                            val detection = results[0]
                                                            if (detection.categories.isNotEmpty()) {
                                                                val category_info = detection.categories[0]
                                                                val label = category_info.label
                                                                val score = category_info.score

                                                                // 只在信心度足夠高時更新結果
                                                                if (score > 0.3f) {
                                                                    detectedItem = label
                                                                    category = classifyItem(label)
                                                                    confidence = score
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    detectedItem = "辨識錯誤"
                                                    category = "錯誤"
                                                } finally {
                                                    imageProxy.close()
                                                }
                                            }
                                        }

                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            ctx as LifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageAnalyzer
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // ===== Card 顯示結果 =====
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("辨識到的物品", style = MaterialTheme.typography.titleMedium)
                            Text(
                                detectedItem,
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp)
                            )

                            if (confidence > 0) {
                                Text(
                                    "信心度: ${String.format("%.1f%%", confidence * 100)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text("分類結果", style = MaterialTheme.typography.titleMedium)
                            Text(
                                category,
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
                                color = when {
                                    category.contains("回收") -> MaterialTheme.colorScheme.primary
                                    category.contains("一般垃圾") -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                            )
                        }
                    }

                    // 狀態指示
                    Text(
                        text = if (objectDetector != null) "模型已載入" else "模型載入失敗",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (objectDetector != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}