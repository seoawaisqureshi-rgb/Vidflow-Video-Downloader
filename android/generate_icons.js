// Generate VidFlow PNG launcher icons using Canvas API via Node.js
const { createCanvas } = require('canvas');
const fs = require('fs');
const path = require('path');

// Icon sizes for each density
const sizes = [
  { dir: 'mipmap-mdpi',    size: 48 },
  { dir: 'mipmap-hdpi',    size: 72 },
  { dir: 'mipmap-xhdpi',   size: 96 },
  { dir: 'mipmap-xxhdpi',  size: 144 },
  { dir: 'mipmap-xxxhdpi', size: 192 },
];

function drawIcon(size) {
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');
  const r = size * 0.18; // corner radius

  // Background gradient: dark purple/navy
  const bg = ctx.createLinearGradient(0, 0, size, size);
  bg.addColorStop(0, '#0F172A');
  bg.addColorStop(0.5, '#1E1B4B');
  bg.addColorStop(1, '#0B0E17');

  // Rounded rect background
  ctx.beginPath();
  ctx.moveTo(r, 0);
  ctx.lineTo(size - r, 0);
  ctx.quadraticCurveTo(size, 0, size, r);
  ctx.lineTo(size, size - r);
  ctx.quadraticCurveTo(size, size, size - r, size);
  ctx.lineTo(r, size);
  ctx.quadraticCurveTo(0, size, 0, size - r);
  ctx.lineTo(0, r);
  ctx.quadraticCurveTo(0, 0, r, 0);
  ctx.closePath();
  ctx.fillStyle = bg;
  ctx.fill();

  // Center card (rounded rect)
  const pad = size * 0.14;
  const cardR = size * 0.12;
  const cx = pad, cy = pad * 1.1;
  const cw = size - pad * 2, ch = size - pad * 2.2;

  const cardGrad = ctx.createLinearGradient(cx, cy, cx + cw, cy + ch);
  cardGrad.addColorStop(0, '#6D28D9');
  cardGrad.addColorStop(0.5, '#7C3AED');
  cardGrad.addColorStop(1, '#0EA5E9');

  ctx.beginPath();
  ctx.moveTo(cx + cardR, cy);
  ctx.lineTo(cx + cw - cardR, cy);
  ctx.quadraticCurveTo(cx + cw, cy, cx + cw, cy + cardR);
  ctx.lineTo(cx + cw, cy + ch - cardR);
  ctx.quadraticCurveTo(cx + cw, cy + ch, cx + cw - cardR, cy + ch);
  ctx.lineTo(cx + cardR, cy + ch);
  ctx.quadraticCurveTo(cx, cy + ch, cx, cy + ch - cardR);
  ctx.lineTo(cx, cy + cardR);
  ctx.quadraticCurveTo(cx, cy, cx + cardR, cy);
  ctx.closePath();
  ctx.fillStyle = cardGrad;
  ctx.fill();

  // White play triangle
  const mx = size * 0.5, my = size * 0.48;
  const ps = size * 0.18;
  ctx.beginPath();
  ctx.moveTo(mx - ps * 0.55, my - ps * 0.7);
  ctx.lineTo(mx + ps * 0.85, my);
  ctx.lineTo(mx - ps * 0.55, my + ps * 0.7);
  ctx.closePath();
  ctx.fillStyle = 'rgba(255,255,255,0.95)';
  ctx.fill();

  // Download arrow below play icon
  const sw = size * 0.065;
  const ax = size * 0.5 - sw / 2;
  const ay = size * 0.64;
  const ah = size * 0.1;
  const aw = size * 0.2;
  const ah2 = size * 0.09;

  // Arrow stem
  ctx.fillStyle = 'rgba(255,255,255,0.95)';
  ctx.fillRect(ax, ay, sw, ah);

  // Arrow head
  ctx.beginPath();
  ctx.moveTo(size * 0.5 - aw * 0.5, ay + ah);
  ctx.lineTo(size * 0.5, ay + ah + ah2);
  ctx.lineTo(size * 0.5 + aw * 0.5, ay + ah);
  ctx.closePath();
  ctx.fill();

  // Tray line
  ctx.fillRect(size * 0.5 - aw * 0.5, ay + ah + ah2 + size * 0.02, aw, sw * 0.85);

  return canvas;
}

const baseDir = path.join(__dirname, 'app', 'src', 'main', 'res');

let canvasAvailable = false;
try {
  require.resolve('canvas');
  canvasAvailable = true;
} catch(e) {}

if (!canvasAvailable) {
  // Fallback: write a simple SVG-based PNG using Buffer (pure Node, no canvas)
  // We'll write a minimal valid 1x1 PNG as placeholder, then handle via Android vector
  console.log('canvas module not available — icon will use Android vector adaptive icon only');
  console.log('For legacy devices, copy a PNG manually or install: npm install canvas');
  process.exit(0);
}

sizes.forEach(({ dir, size }) => {
  const outDir = path.join(baseDir, dir);
  fs.mkdirSync(outDir, { recursive: true });

  const canvas = drawIcon(size);
  const buffer = canvas.toBuffer('image/png');
  const outPath = path.join(outDir, 'ic_launcher.png');
  const outPathRound = path.join(outDir, 'ic_launcher_round.png');
  fs.writeFileSync(outPath, buffer);
  fs.writeFileSync(outPathRound, buffer);
  console.log(`✓ ${dir}/ic_launcher.png (${size}x${size})`);
});

console.log('\nAll icons generated successfully!');
