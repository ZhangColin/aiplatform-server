// 极简静态文件服务器（demo 预览示意用，零依赖）
// 用法：node /opt/serve.js <root> <port>
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || '/workspace';
const port = Number(process.argv[3] || 8081);
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

http.createServer((req, res) => {
  const p = decodeURIComponent(req.url.split('?')[0]);
  const file = path.join(root, p === '/' ? '/index.html' : p);
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 not found: ' + p + '\n(agent 还没写出这个文件？)');
      return;
    }
    res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(port, '0.0.0.0', () => {
  console.log('serving ' + root + ' on ' + port);
});
