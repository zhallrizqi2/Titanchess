/**
 * Titan Chess - Board Analyzer Engine
 * Menggunakan TensorFlow.js untuk mendeteksi bidak catur dari gambar 8x8 petak otomatis
 */

// Konstanta Ukuran Input Model TensorFlow
const SQUARE_SIZE = 32; 
let model = null;

// Context memory canvas untuk manipulasi sub-petak gambar
const memoryCanvas = document.createElement("canvas");
memoryCanvas.width = SQUARE_SIZE;
memoryCanvas.height = SQUARE_SIZE;
const memoryCtx = memoryCanvas.getContext("2d");

/**
 * Log Helper untuk mengirim pesan debug dari WebView ke Notification Android
 */
function logToAndroid(message) {
  console.log(message);
  if (window.AndroidBridge && typeof window.AndroidBridge.onLog === 'function') {
    window.AndroidBridge.onLog(message);
  }
}

/**
 * Memuat Model TensorFlow.js (Aset Lokal)
 */
async function loadModels() {
  if (model !== null) return;
  try {
    logToAndroid("Memuat model tf.loadLayersModel...");
    // Mengarah ke folder assets lokal di Android wrapper
    model = await tf.loadLayersModel("./model/model.json");
    logToAndroid("Model TensorFlow berhasil dimuat!");
  } catch (err) {
    logToAndroid("Gagal memuat model: " + err.message);
    if (window.AndroidBridge && typeof window.AndroidBridge.onError === 'function') {
      window.AndroidBridge.onError("LoadModelError: " + err.message);
    }
  }
}

/**
 * Mengubah String Data URL / Base64 menjadi objek Image HTML5
 */
function loadImageFromDataUrl(dataUrl) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = (err) => reject(err);
    img.src = dataUrl;
  });
}

/**
 * Fungsi Utama: Memproses Gambar dari Kotlin dan Mengonversinya Menjadi FEN
 */
async function imageToFen(dataUrl) {
  logToAndroid("imageToFen mulai, load model...");
  await loadModels();
  logToAndroid("Model siap, memproses gambar...");
  const img = await loadImageFromDataUrl(dataUrl);

  const canvas = document.createElement("canvas");
  canvas.width = img.width;
  canvas.height = img.height;
  const ctx = canvas.getContext("2d");
  ctx.drawImage(img, 0, 0);

  logToAndroid(`Menerima gambar Auto-Crop: ${img.width}x${img.height}`);

  // Karena gambar dari Kotlin sudah berupa kotak persegi murni 1:1, bagi koordinat secara rata 8x8
  const squareW = img.width / 8;
  const squareH = img.height / 8;
  const allSquaresGray = [];

  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 8; c++) {
      memoryCtx.clearRect(0, 0, SQUARE_SIZE, SQUARE_SIZE);
      
      // Potong sub-gambar per petak catur
      memoryCtx.drawImage(
        canvas,
        c * squareW,
        r * squareH,
        squareW,
        squareH,
        0,
        0,
        SQUARE_SIZE,
        SQUARE_SIZE
      );
      
      const imgData = memoryCtx.getImageData(0, 0, SQUARE_SIZE, SQUARE_SIZE);
      const { data } = imgData;
      const gray = new Float32Array(SQUARE_SIZE * SQUARE_SIZE);
      
      // Formulasi ekstraksi warna grayscale (hitam putih) untuk input tensor
      for (let i = 0; i < SQUARE_SIZE * SQUARE_SIZE; i++) {
        gray[i] = (0.299 * data[i * 4] + 0.587 * data[i * 4 + 1] + 0.114 * data[i * 4 + 2]) / 255.0;
      }
      allSquaresGray.push(gray);
    }
  }

  logToAndroid("Ekstraksi 64 petak selesai. Menjalankan TensorFlow...");
  const allLabels = await classifyAllSquares(allSquaresGray);

  const board = [];
  for (let r = 0; r < 8; r++) {
    const rowPieces = allLabels.slice(r * 8, (r + 1) * 8);
    board.push(rowPieces);
  }

  return boardToFen(board, true);
}

/**
 * Melakukan Prediksi Batch Menggunakan Model TensorFlow untuk 64 Petak Sekaligus
 */
async function classifyAllSquares(allSquaresGray) {
  // Daftar label output kelas sesuai urutan index model AI catur kamu
  // Sesuaikan urutan array ini jika model kamu menggunakan indeks label berbeda!
  const CLASSES = ["1", "B", "K", "N", "P", "Q", "R", "b", "k", "n", "p", "q", "r"];
  const labels = [];

  tf.tidy(() => {
    // Gabungkan 64 array petak menjadi satu bentuk matriks tensor batch [64, 32, 32, 1]
    const tensors = allSquaresGray.map(gray => tf.tensor2d(gray, [SQUARE_SIZE, SQUARE_SIZE]).expandDims(-1));
    const batchTensor = tf.stack(tensors);

    // Jalankan kalkulasi prediksi AI
    const predictions = model.predict(batchTensor);
    const argmax = predictions.argMax(-1);
    const indices = argmax.dataSync();

    for (let i = 0; i < indices.length; i++) {
      labels.push(CLASSES[indices[i]]);
    }
  });

  return labels;
}

/**
 * Mengonversi Matriks Array 2D Bidak Menjadi String Notasi FEN Standar Catur
 */
function boardToFen(board, activeColorWhite = true) {
  const fenRows = [];
  for (let r = 0; r < 8; r++) {
    let emptyCount = 0;
    let rowStr = "";
    for (let c = 0; c < 8; c++) {
      const p = board[r][c];
      if (p === "1" || p === "empty" || !p) {
        emptyCount++;
      } else {
        if (emptyCount > 0) {
          rowStr += emptyCount;
          emptyCount = 0;
        }
        rowStr += p;
      }
    }
    if (emptyCount > 0) {
      rowStr += emptyCount;
    }
    fenRows.push(rowStr);
  }
  
  const turn = activeColorWhite ? "w" : "b";
  return fenRows.join("/") + ` ${turn} - - 0 1`;
}

/**
 * Fungsi Jembatan Global yang dipanggil langsung oleh evaluateJavascript dari Android Kotlin
 */
async function analyzeImage(dataUrl) {
  try {
    const fenResult = await imageToFen(dataUrl);
    logToAndroid("Analisis sukses! Mengirim FEN ke Android...");
    if (window.AndroidBridge && typeof window.AndroidBridge.onFenResult === 'function') {
      window.AndroidBridge.onFenResult(fenResult);
    }
  } catch (err) {
    logToAndroid("Gagal memproses analyzeImage: " + err.message);
    if (window.AndroidBridge && typeof window.AndroidBridge.onError === 'function') {
      window.AndroidBridge.onError(err.message);
    }
  }
        }
