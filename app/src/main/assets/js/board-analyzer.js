const SQUARE_SIZE = 32;
const PIECE_LABELS = [
  "empty", "wB", "wK", "wN", "wP", "wQ", "wR",
  "bB", "bK", "bN", "bP", "bQ", "bR",
];

let pieceModel = null;

async function loadModels() {
  if (!pieceModel) {
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
    img.onerror = reject;
    img.src = dataUrl;
  });
}

function getSquareGrayscaleFloat32(ctx, col, row, squareW, squareH) {
  const canvas = document.createElement("canvas");
  canvas.width = SQUARE_SIZE;
  canvas.height = SQUARE_SIZE;
  const c2 = canvas.getContext("2d");
  c2.drawImage(
    ctx.canvas,
    col * squareW,
    row * squareH,
    squareW,
    squareH,
    0,
    0,
    SQUARE_SIZE,
    SQUARE_SIZE
  );
  const imgData = c2.getImageData(0, 0, SQUARE_SIZE, SQUARE_SIZE);
  const { data } = imgData;
  const gray = new Float32Array(SQUARE_SIZE * SQUARE_SIZE);
  for (let i = 0; i < SQUARE_SIZE * SQUARE_SIZE; i++) {
    const r = data[i * 4];
    const g = data[i * 4 + 1];
    const b = data[i * 4 + 2];
    gray[i] = 0.299 * r + 0.587 * g + 0.114 * b;
  }
  return gray;
}

let loggedInputNames = false;

async function classifySquare(grayFloat32) {
  const pixelInputName = pieceModel.inputNodes.find((n) => !/keep/i.test(n)) || pieceModel.inputNodes[0];
  const keepInputName = pieceModel.inputNodes.find((n) => /keep/i.test(n));

  if (!loggedInputNames) {
    loggedInputNames = true;
    logToAndroid("pixelInput=" + pixelInputName + " keepInput=" + keepInputName);
  }

  const input = tf.tensor2d(grayFloat32, [1, SQUARE_SIZE * SQUARE_SIZE]);
  const keepProb = tf.scalar(1.0);

  const inputDict = {};
  inputDict[pixelInputName] = input;
  if (keepInputName) {
    inputDict[keepInputName] = keepProb;
  }

  let output;
  try {
    output = pieceModel.execute(inputDict);
  } catch (e) {
    input.dispose();
    const input4d = tf.tensor4d(grayFloat32, [1, SQUARE_SIZE, SQUARE_SIZE, 1]);
    const inputDict4d = {};
    inputDict4d[pixelInputName] = input4d;
    if (keepInputName) {
      inputDict4d[keepInputName] = keepProb;
    }
    output = pieceModel.execute(inputDict4d);
    input4d.dispose();
  }
  const data = await output.data();
  input.dispose();
  keepProb.dispose();
  output.dispose();

  let maxIdx = 0;
  let maxVal = -Infinity;
  for (let i = 0; i < data.length; i++) {
    if (data[i] > maxVal) {
      maxVal = data[i];
      maxIdx = i;
    }
  }
  return PIECE_LABELS[maxIdx];
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
  logToAndroid("Model siap, load gambar...");
  const img = await loadImageFromDataUrl(dataUrl);
  logToAndroid("Gambar dimuat: " + img.width + "x" + img.height);

  const canvas = document.getElementById("tv-work-canvas");
  canvas.width = img.width;
  canvas.height = img.height;
  const ctx = canvas.getContext("2d");
  ctx.drawImage(img, 0, 0);

  const squareW = img.width / 8;
  const squareH = img.height / 8;

  const board = [];
  for (let r = 0; r < 8; r++) {
    const rowPieces = [];
    for (let c = 0; c < 8; c++) {
      const gray = getSquareGrayscaleFloat32(ctx, c, r, squareW, squareH);
      const label = await classifySquare(gray);
      rowPieces.push(label);
    }
    board.push(rowPieces);
    logToAndroid("Baris " + (r + 1) + "/8 selesai");
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
    logToAndroid("ERROR analyzeImage: " + (e && e.message ? e.message : String(e)));
    if (window.AndroidBridge && window.AndroidBridge.onError) {
      window.AndroidBridge.onError(String(e && e.message ? e.message : e));
    }
  }
}

window.addEventListener("load", () => {
  logToAndroid("WebView halaman dimuat, siap analisis");
});
