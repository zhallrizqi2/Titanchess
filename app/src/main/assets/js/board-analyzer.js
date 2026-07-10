const SQUARE_SIZE = 32;
const PIECE_LABELS = [
  "empty", "wB", "wK", "wN", "wP", "wQ", "wR",
  "bB", "bK", "bN", "bP", "bQ", "bR",
];

let pieceModel = null;
let loggedInputNames = false;

// Canvas memori global untuk resize petak menjadi 32x32
const memoryCanvas = document.createElement("canvas");
memoryCanvas.width = SQUARE_SIZE;
memoryCanvas.height = SQUARE_SIZE;
const memoryCtx = memoryCanvas.getContext("2d");

async function loadModels() {
  if (!pieceModel) {
    // --- PERBAIKAN UNTUK FULL ANDROID WEBVIEW ---
    // Paksa menggunakan CPU untuk menghindari crash WebGL di WebView
    try {
      await tf.setBackend('cpu');
      logToAndroid("Backend TensorFlow diatur ke: CPU");
    } catch (err) {
      logToAndroid("Gagal mengatur backend ke CPU: " + err.message);
    }

    pieceModel = await tf.loadFrozenModel(
      "model/frozen_model/tensorflowjs_model.pb",
      "model/frozen_model/weights_manifest.json"
    );
    logToAndroid("Model dimuat. inputNodes=" + JSON.stringify(pieceModel.inputNodes));
  }
}

function logToAndroid(message) {
  try {
    if (window.AndroidBridge && window.AndroidBridge.onLog) {
      window.AndroidBridge.onLog(String(message));
    }
  } catch (e) {}
  console.log(message);
}

function loadImageFromDataUrl(dataUrl) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("Gagal memuat gambar dari Data URL"));
    img.src = dataUrl;
  });
}

// --- PERBAIKAN URUTAN ARRAY BATCH UNTUK FROZEN MODEL ---
async function classifyAllSquares(allSquaresGray) {
  const pixelInputName = pieceModel.inputNodes.find((n) => !/keep/i.test(n)) || pieceModel.inputNodes[0];
  const keepInputName = pieceModel.inputNodes.find((n) => /keep/i.test(n));

  if (!loggedInputNames) {
    loggedInputNames = true;
    logToAndroid("pixelInput=" + pixelInputName + " keepInput=" + keepInputName);
  }

  // Alokasikan memori tepat 64 petak x 1024 pixel (32x32)
  const flatAllData = new Float32Array(64 * SQUARE_SIZE * SQUARE_SIZE);
  
  // Gabungkan dengan urutan sekuensial yang murni per sub-array petak
  for (let i = 0; i < 64; i++) {
    const squareData = allSquaresGray[i];
    for (let p = 0; p < 1024; p++) {
      flatAllData[i * 1024 + p] = squareData[p];
    }
  }

  const outputTensor = tf.tidy(() => {
    const keepProb = tf.scalar(1.0);
    let input;
    let inputDict = {};

    try {
      // Sesuai log kamu (pixelInput=Input), format utama biasanya berbentuk Flat 2D [64, 1024]
      input = tf.tensor2d(flatAllData, [64, SQUARE_SIZE * SQUARE_SIZE]);
      inputDict[pixelInputName] = input;
      if (keepInputName) inputDict[keepInputName] = keepProb;
      return pieceModel.execute(inputDict);
    } catch (e) {
      // Fallback jika model meminta format matriks gambar 4D [64, 32, 32, 1]
      input = tf.tensor4d(flatAllData, [64, SQUARE_SIZE, SQUARE_SIZE, 1]);
      inputDict = {};
      inputDict[pixelInputName] = input;
      if (keepInputName) inputDict[keepInputName] = keepProb;
      return pieceModel.execute(inputDict);
    }
  });

  const predictionsData = await outputTensor.data();
  outputTensor.dispose();

  const numClasses = PIECE_LABELS.length;
  const labelsResult = [];

  // Ambil hasil prediksi per petak dari total 64 baris data keluaran
  for (let b = 0; b < 64; b++) {
    let maxIdx = 0;
    let maxVal = -Infinity;
    const offset = b * numClasses;

    for (let i = 0; i < numClasses; i++) {
      const val = predictionsData[offset + i];
      if (val > maxVal) {
        maxVal = val;
        maxIdx = i;
      }
    }
    labelsResult.push(PIECE_LABELS[maxIdx]);
  }

  return labelsResult;
}


function boardToFen(board, whiteToMove = true) {
  const rows = [];
  for (let r = 0; r < 8; r++) {
    let empty = 0;
    let rowStr = "";
    for (let c = 0; c < 8; c++) {
      const piece = board[r][c];
      if (piece === "empty") {
        empty++;
      } else {
        if (empty > 0) {
          rowStr += empty;
          empty = 0;
        }
        const color = piece[0];
        const type = piece[1];
        rowStr += color === "w" ? type : type.toLowerCase();
      }
    }
    if (empty > 0) rowStr += empty;
    rows.push(rowStr);
  }
  const placement = rows.join("/");
  return `${placement} ${whiteToMove ? "w" : "b"} - - 0 1`;
}

async function imageToFen(dataUrl) {
  logToAndroid("imageToFen mulai, load model...");
  await loadModels();
  logToAndroid("Model siap, memproses gambar...");
  const img = await loadImageFromDataUrl(dataUrl);

  // Buat canvas offscreen sesuai ukuran asli gambar yang dikirim oleh Android
  const canvas = document.createElement("canvas");
  canvas.width = img.width;
  canvas.height = img.height;
  const ctx = canvas.getContext("2d");
  ctx.drawImage(img, 0, 0);

  logToAndroid(`Membaca resolusi asli Android: ${img.width}x${img.height}`);

  // --- PERBAIKAN: Bagi 8 secara dinamis sesuai resolusi asli potongan Android ---
  const squareW = img.width / 8;
  const squareH = img.height / 8;
  const allSquaresGray = [];

  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 8; c++) {
      memoryCtx.clearRect(0, 0, SQUARE_SIZE, SQUARE_SIZE);
      
      // Potong petak langsung dari koordinat gambar asli tanpa offset buatan
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

async function analyzeImage(dataUrl) {
  try {
    const fen = await imageToFen(dataUrl);
    if (window.AndroidBridge && window.AndroidBridge.onFenResult) {
      window.AndroidBridge.onFenResult(fen);
    }
  } catch (e) {
    const errMsg = e && e.message ? e.message : String(e);
    logToAndroid("ERROR UTAMA: " + errMsg);
    
    if (window.AndroidBridge && window.AndroidBridge.onFenResult) {
      // Kirim detail pesan error ke sistem notifikasi Android biar kelihatan jelas errornya apa
      window.AndroidBridge.onFenResult("ERROR: " + errMsg);
    }
  }
}

window.addEventListener("load", () => {
  logToAndroid("WebView halaman dimuat, siap analisis");
});
        
