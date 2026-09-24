#!/usr/bin/env python3
"""Tiny local server for News Photos: serves index.html and proxies RSS feeds / images with CORS.
Usage: python3 serve.py  ->  http://localhost:8765
"""
import http.server, urllib.request, urllib.parse, socketserver, sys, os

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
os.chdir(os.path.dirname(os.path.abspath(__file__)))

class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        p = urllib.parse.urlparse(self.path)
        if p.path in ('/proxy', '/img'):
            url = urllib.parse.parse_qs(p.query).get('url', [''])[0]
            if not url.startswith(('http://', 'https://')):
                self.send_error(400, 'bad url'); return
            try:
                req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (NewsPhotos local proxy)', 'Accept': '*/*'})
                with urllib.request.urlopen(req, timeout=20) as r:
                    data = r.read()
                    ctype = r.headers.get('Content-Type', 'application/octet-stream')
            except Exception as e:
                self.send_error(502, str(e)); return
            self.send_response(200)
            self.send_header('Content-Type', ctype)
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Cache-Control', 'max-age=300')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        super().do_GET()

    def log_message(self, fmt, *args):
        sys.stderr.write('%s %s\n' % (self.address_string(), fmt % args))

class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

if __name__ == '__main__':
    print(f'News Photos running at http://localhost:{PORT}  (Ctrl+C to stop)')
    Server(('', PORT), Handler).serve_forever()
