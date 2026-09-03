package com.crispy.tv.plugins.runtime

internal object PluginJsFacade {

    fun build(scraperIdJson: String, settingsJson: String): String {
        return """
            globalThis.SCRAPER_ID = $scraperIdJson;
            globalThis.SCRAPER_SETTINGS = $settingsJson;
            if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
            if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
            if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;
            if (typeof globalThis.console === 'undefined') {
              globalThis.console = {
                log: function() { var a = Array.prototype.slice.call(arguments).map(String); __crispyLog(a.join(' ')); },
                warn: function() { var a = Array.prototype.slice.call(arguments).map(String); __crispyLog('WARN ' + a.join(' ')); },
                error: function() { var a = Array.prototype.slice.call(arguments).map(String); __crispyLog('ERROR ' + a.join(' ')); },
                info: function() { var a = Array.prototype.slice.call(arguments).map(String); __crispyLog(a.join(' ')); }
              };
            }

            ${fetchPolyfill()}
            ${base64Polyfill()}
            ${urlPolyfill()}
            ${cryptoPolyfill()}
            ${domPolyfill()}
            ${storagePolyfill()}
            ${requirePolyfill()}
        """.trimIndent()
    }

    private fun fetchPolyfill() = """
        function __normalizeFetchHeaders(headers) {
          var out = {};
          if (!headers) return out;
          if (Array.isArray(headers)) {
            headers.forEach(function(pair) { if (pair && pair.length >= 2) out[pair[0]] = String(pair[1]); });
            return out;
          }
          Object.keys(headers).forEach(function(key) { out[key] = String(headers[key]); });
          return out;
        }

        globalThis.fetch = async function(url, options) {
          options = options || {};
          var method = (options.method || 'GET').toUpperCase();
          var headers = __normalizeFetchHeaders(options.headers);
          var body = options.body == null ? '' : String(options.body);
          var requestJson = JSON.stringify({ url: url, method: method, headers: headers, body: body });
          var parsed = JSON.parse(await __crispyFetch(requestJson));
          return {
            ok: parsed.status >= 200 && parsed.status < 300,
            status: parsed.status,
            statusText: '',
            url: url,
            headers: {
              get: function(name) { return parsed.headers[String(name).toLowerCase()] || null; },
              has: function(name) { return Object.prototype.hasOwnProperty.call(parsed.headers, String(name).toLowerCase()); }
            },
            text: async function() { return parsed.body; },
            json: async function() {
              if (!parsed.body) return null;
              try { return JSON.parse(parsed.body); } catch (e) { return null; }
            }
          };
        };

        if (typeof AbortController === 'undefined') {
          globalThis.AbortController = function() { this.signal = { aborted: false }; this.abort = function() { this.signal.aborted = true; }; };
        }
        if (typeof AbortSignal === 'undefined') {
          globalThis.AbortSignal = { timeout: function() { return { aborted: false }; } };
        }
    """.trimIndent()

    private fun base64Polyfill() = """
        globalThis.atob = function(input) { return __crispyBase64DecodeText(String(input)); };
        globalThis.btoa = function(input) { return __crispyBase64EncodeText(String(input)); };

        if (typeof TextEncoder === 'undefined') {
          globalThis.TextEncoder = function() {};
          TextEncoder.prototype.encode = function(str) {
            var bytes = new Uint8Array(__crispyUtf8ToBytesJson(String(str)));
            return bytes;
          };
        }
        if (typeof TextDecoder === 'undefined') {
          globalThis.TextDecoder = function() {};
          TextDecoder.prototype.decode = function(data) {
            var bytes = data;
            if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
            var hex = '';
            for (var i = 0; i < bytes.length; i++) hex += (bytes[i] & 0xff).toString(16).padStart(2, '0');
            return __crispyBytesToUtf8(hex);
          };
        }
    """.trimIndent()

    private fun urlPolyfill() = """
        function __parseUrl(urlString) { return __crispyParseUrl(String(urlString)); }
        globalThis.__parseUrl = __parseUrl;

        function URLSearchParams(init) {
          this._params = {};
          var self = this;
          if (init && typeof init === 'object' && !Array.isArray(init)) {
            Object.keys(init).forEach(function(key) { self._params[key] = String(init[key]); });
          } else if (typeof init === 'string') {
            init.replace(/^\?/, '').split('&').forEach(function(pair) {
              if (!pair) return;
              var parts = pair.split('=');
              self._params[decodeURIComponent(parts[0] || '')] = decodeURIComponent((parts[1] || '').replace(/\+/g, ' '));
            });
          }
        }
        URLSearchParams.prototype.append = function(key, value) { this._params[key] = String(value); };
        URLSearchParams.prototype.set = function(key, value) { this._params[key] = String(value); };
        URLSearchParams.prototype.get = function(key) { return Object.prototype.hasOwnProperty.call(this._params, key) ? this._params[key] : null; };
        URLSearchParams.prototype.has = function(key) { return Object.prototype.hasOwnProperty.call(this._params, key); };
        URLSearchParams.prototype.delete = function(key) { delete this._params[key]; };
        URLSearchParams.prototype.keys = function() { return Object.keys(this._params); };
        URLSearchParams.prototype.values = function() { var s = this; return Object.keys(this._params).map(function(k) { return s._params[k]; }); };
        URLSearchParams.prototype.entries = function() { var s = this; return Object.keys(this._params).map(function(k) { return [k, s._params[k]]; }); };
        URLSearchParams.prototype.toString = function() {
          var s = this;
          return Object.keys(this._params).map(function(k) { return encodeURIComponent(k) + '=' + encodeURIComponent(s._params[k]); }).join('&');
        };
        URLSearchParams.prototype.forEach = function(callback) {
          var s = this;
          Object.keys(this._params).forEach(function(k) { callback(s._params[k], k, s); });
        };

        function URL(urlString, base) {
          var full = String(urlString);
          if (base && !/^[a-z][a-z0-9+.-]*:/i.test(full)) {
            var baseUrl = typeof base === 'string' ? base : String(base.href);
            full = __crispyResolve(baseUrl, full);
          }
          var data = JSON.parse(__parseUrl(full));
          this.href = full;
          this.protocol = data.protocol;
          this.host = data.host;
          this.hostname = data.hostname;
          this.port = data.port;
          this.pathname = data.pathname;
          this.search = data.search;
          this.hash = data.hash;
          this.origin = data.protocol ? data.protocol + '//' + data.host : '';
          this.searchParams = new URLSearchParams(data.search || '');
        }
        URL.prototype.toString = function() { return this.href; };
        globalThis.URL = URL;
        globalThis.URLSearchParams = URLSearchParams;
        globalThis.encodeURIComponent = encodeURIComponent;
        globalThis.decodeURIComponent = decodeURIComponent;
        globalThis.encodeURI = encodeURI;
        globalThis.decodeURI = decodeURI;
    """.trimIndent()

    private fun cryptoPolyfill() = """
        function __bytesToHex(bytes) {
          var out = [];
          for (var i = 0; i < bytes.length; i++) out.push((bytes[i] & 0xff).toString(16).padStart(2, '0'));
          return out.join('');
        }
        function __hexToBytes(hex) {
          hex = String(hex || '').replace(/[^0-9a-fA-F]/g, '');
          if (hex.length % 2) hex = '0' + hex;
          var bytes = new Uint8Array(hex.length / 2);
          for (var i = 0; i < hex.length; i += 2) bytes[i / 2] = parseInt(hex.substr(i, 2), 16);
          return bytes;
        }
        function __concatBytes() {
          var total = 0, parts = [];
          for (var i = 0; i < arguments.length; i++) { parts.push(arguments[i]); total += arguments[i].length; }
          var out = new Uint8Array(total), offset = 0;
          for (var j = 0; j < parts.length; j++) { out.set(parts[j], offset); offset += parts[j].length; }
          return out;
        }
        function __normalizeHashName(hash) {
          var name = hash && hash.name ? hash.name : hash;
          name = String(name || 'SHA-256').toUpperCase().replace(/[^A-Z0-9]/g, '');
          if (['SHA1','SHA256','SHA384','SHA512','MD5'].indexOf(name) >= 0) return name;
          throw new Error('Unsupported hash algorithm: ' + name);
        }
        function __normalizeAlgorithmName(algo) {
          var name = algo && algo.name ? algo.name : algo;
          name = String(name || '').toUpperCase();
          if (name.indexOf('AES-GCM') >= 0) return 'AES-GCM';
          if (name.indexOf('AES-CBC') >= 0) return 'AES-CBC';
          if (name.indexOf('AES-ECB') >= 0 || name === 'ECB') return 'AES-ECB';
          return name;
        }
        function __aesModeName(mode) { return __normalizeAlgorithmName(mode || 'AES-CBC'); }
        function __digestBytes(hash, bytes) { return __hexToBytes(__crispyDigestHex(__normalizeHashName(hash), __bytesToHex(bytes))); }
        function __hmacBytes(hash, keyBytes, dataBytes) { return __hexToBytes(__crispyHmacHex(__normalizeHashName(hash), __bytesToHex(keyBytes), __bytesToHex(dataBytes))); }
        function __aesBytes(encrypt, mode, keyBytes, ivBytes, dataBytes) {
          var fn = encrypt ? __crispyAesEncryptHex : __crispyAesDecryptHex;
          return __hexToBytes(fn(__aesModeName(mode), __bytesToHex(keyBytes), __bytesToHex(ivBytes), __bytesToHex(dataBytes)));
        }
        function __utf8Bytes(str) { return __hexToBytes(__crispyUtf8ToHex(String(str))); }
        function __bytesToUtf8(bytes) { return __crispyBytesToUtf8(__bytesToHex(bytes)); }

        globalThis.crypto = {
          getRandomValues: function(arr) {
            if (!arr) return arr;
            var random = __hexToBytes(__crispyRandomHex(arr.length));
            for (var i = 0; i < arr.length; i++) arr[i] = random[i] || 0;
            return arr;
          },
          randomUUID: function() {
            var b = new Uint8Array(16);
            globalThis.crypto.getRandomValues(b);
            b[6] = (b[6] & 0x0f) | 0x40;
            b[8] = (b[8] & 0x3f) | 0x80;
            var h = __bytesToHex(b);
            return h.substr(0, 8) + '-' + h.substr(8, 4) + '-' + h.substr(12, 4) + '-' + h.substr(16, 4) + '-' + h.substr(20);
          },
          subtle: {
            digest: async function(algo, data) { return __digestBytes(algo, new Uint8Array(data)).buffer; },
            encrypt: async function(params, key, data) {
              var mode = __normalizeAlgorithmName(params);
              if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported encrypt algorithm: ' + mode);
              return __aesBytes(true, mode, new Uint8Array(key._raw), new Uint8Array(params.iv || []), new Uint8Array(data)).buffer;
            },
            decrypt: async function(params, key, data) {
              var mode = __normalizeAlgorithmName(params);
              if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported decrypt algorithm: ' + mode);
              return __aesBytes(false, mode, new Uint8Array(key._raw), new Uint8Array(params.iv || []), new Uint8Array(data)).buffer;
            },
            importKey: async function(fmt, data, algo, extractable, usages) {
              return { type: 'secret', extractable: true, algorithm: algo, usages: usages || [], _raw: new Uint8Array(data) };
            },
            exportKey: async function(fmt, key) { return key._raw.buffer; },
            sign: async function(algo, key, data) {
              var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
              return __hmacBytes(hash, new Uint8Array(key._raw), new Uint8Array(data)).buffer;
            },
            verify: async function(algo, key, sig, data) {
              var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
              var expected = __hmacBytes(hash, new Uint8Array(key._raw), new Uint8Array(data));
              var actual = new Uint8Array(sig);
              if (expected.length !== actual.length) return false;
              var diff = 0;
              for (var i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
              return diff === 0;
            }
          }
        };

        function WordArray(words, sigBytes) {
          this.words = words || [];
          this.sigBytes = sigBytes != undefined ? sigBytes : this.words.length * 4;
        }
        WordArray.prototype.toString = function(encoder) { return (encoder || CryptoJS.enc.Hex).stringify(this); };
        WordArray.prototype.clamp = function() {
          var words = this.words, sigBytes = this.sigBytes;
          words[sigBytes >>> 2] &= 0xffffffff << (32 - (sigBytes % 4) * 8);
          words.length = Math.ceil(sigBytes / 4);
          return this;
        };
        WordArray.prototype.concat = function(wordArray) {
          this.clamp();
          var thatBytes = __wordArrayToBytes(wordArray);
          var thisBytes = __wordArrayToBytes(this);
          var merged = __concatBytes(thisBytes, thatBytes);
          this.words = __bytesToWords(merged);
          this.sigBytes = merged.length;
          return this;
        };
        function __bytesToWords(bytes) {
          var words = [];
          for (var i = 0; i < bytes.length; i++) words[i >>> 2] |= (bytes[i] & 0xff) << (24 - (i % 4) * 8);
          return words;
        }
        function __wordArrayToBytes(wordArray) {
          if (!wordArray || !Array.isArray(wordArray.words)) return __utf8Bytes(String(wordArray));
          var bytes = new Uint8Array(wordArray.sigBytes);
          for (var i = 0; i < wordArray.sigBytes; i++) bytes[i] = (wordArray.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
          return bytes;
        }
        function __wordArrayCreate(words, sigBytes) { return new WordArray(words, sigBytes); }
        function __isWordArray(value) { return value && typeof value === 'object' && Array.isArray(value.words) && typeof value.sigBytes === 'number'; }
        function __normalizeCryptoInput(value) {
          if (__isWordArray(value)) return __wordArrayToBytes(value);
          if (typeof value === 'string') return __utf8Bytes(value);
          return new Uint8Array(value);
        }
        function __bytesToWordArray(bytes) { return __wordArrayCreate(__bytesToWords(bytes), bytes.length); }

        var CryptoJS = {
          enc: {
            Hex: {
              stringify: function(wa) { return __bytesToHex(__wordArrayToBytes(wa)); },
              parse: function(s) { return __bytesToWordArray(__hexToBytes(s)); }
            },
            Utf8: {
              stringify: function(wa) { return __bytesToUtf8(__wordArrayToBytes(wa)); },
              parse: function(s) { return __bytesToWordArray(__utf8Bytes(s)); }
            },
            Latin1: {
              stringify: function(wa) {
                var bytes = __wordArrayToBytes(wa), out = '';
                for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
                return out;
              },
              parse: function(s) {
                s = String(s || '');
                var bytes = new Uint8Array(s.length);
                for (var i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i) & 0xff;
                return __bytesToWordArray(bytes);
              }
            },
            Base64: {
              stringify: function(wa) { return __crispyBase64EncodeHex(__bytesToHex(__wordArrayToBytes(wa))); },
              parse: function(s) { return __bytesToWordArray(__hexToBytes(__crispyBase64DecodeHex(s))); }
            }
          },
          lib: {
            WordArray: {
              create: function(words, sigBytes) {
                if (words == null) return __wordArrayCreate([], sigBytes || 0);
                if (__isWordArray(words)) return words;
                if (typeof words === 'string') return CryptoJS.enc.Utf8.parse(words);
                return __wordArrayCreate(words, sigBytes);
              }
            }
          },
          MD5: function(m) { return __bytesToWordArray(__digestBytes('MD5', __normalizeCryptoInput(m))); },
          SHA1: function(m) { return __bytesToWordArray(__digestBytes('SHA1', __normalizeCryptoInput(m))); },
          SHA256: function(m) { return __bytesToWordArray(__digestBytes('SHA256', __normalizeCryptoInput(m))); },
          SHA512: function(m) { return __bytesToWordArray(__digestBytes('SHA512', __normalizeCryptoInput(m))); },
          HmacMD5: function(m, k) { return __bytesToWordArray(__hmacBytes('MD5', __normalizeCryptoInput(k), __normalizeCryptoInput(m))); },
          HmacSHA1: function(m, k) { return __bytesToWordArray(__hmacBytes('SHA1', __normalizeCryptoInput(k), __normalizeCryptoInput(m))); },
          HmacSHA256: function(m, k) { return __bytesToWordArray(__hmacBytes('SHA256', __normalizeCryptoInput(k), __normalizeCryptoInput(m))); },
          HmacSHA512: function(m, k) { return __bytesToWordArray(__hmacBytes('SHA512', __normalizeCryptoInput(k), __normalizeCryptoInput(m))); },
          AES: {
            encrypt: function(message, key, options) {
              options = options || {};
              var data = __normalizeCryptoInput(message);
              var keyBytes = __normalizeCryptoInput(typeof key === 'string' ? CryptoJS.enc.Utf8.parse(key) : key);
              var ivBytes = options.iv ? __normalizeCryptoInput(options.iv) : new Uint8Array(0);
              var mode = options.mode && options.mode.CBC ? __aesModeName(options.mode) : __aesModeName(options.mode || 'AES-CBC');
              var out = __aesBytes(true, mode, keyBytes, ivBytes, data);
              return __bytesToWordArray(out);
            },
            decrypt: function(cipher, key, options) {
              options = options || {};
              var data = __isWordArray(cipher) ? __wordArrayToBytes(cipher) : __hexToBytes(__crispyBase64DecodeHex(String(cipher)));
              var keyBytes = __normalizeCryptoInput(typeof key === 'string' ? CryptoJS.enc.Utf8.parse(key) : key);
              var ivBytes = options.iv ? __normalizeCryptoInput(options.iv) : new Uint8Array(0);
              var mode = options.mode && options.mode.CBC ? __aesModeName(options.mode) : __aesModeName(options.mode || 'AES-CBC');
              return __bytesToWordArray(__aesBytes(false, mode, keyBytes, ivBytes, data));
            }
          }
        };
        CryptoJS.pad = { NoPadding: 'NoPadding', Pkcs7: 'Pkcs7' };
        CryptoJS.mode = { CBC: 'AES-CBC', ECB: 'AES-ECB', GCM: 'AES-GCM' };
        CryptoJS.algo = { MD5: 'MD5', SHA1: 'SHA1', SHA256: 'SHA256', SHA512: 'SHA512' };
        globalThis.CryptoJS = CryptoJS;
    """.trimIndent()

    private fun domPolyfill() = """
        function CheerioWrapper(docId, elementIds) {
          this._docId = docId;
          this._elementIds = elementIds || [];
          this.length = this._elementIds.length;
        }
        function __wrap(docId, ids) { return new CheerioWrapper(docId, ids); }
        CheerioWrapper.prototype.find = function(sel) {
          var all = [];
          for (var i = 0; i < this._elementIds.length; i++) {
            all = all.concat(JSON.parse(__crispyDomFind(this._docId, this._elementIds[i], sel)));
          }
          return __wrap(this._docId, all);
        };
        CheerioWrapper.prototype.text = function() {
          return this._elementIds.length === 0 ? '' : __crispyDomText(this._docId, JSON.stringify(this._elementIds));
        };
        CheerioWrapper.prototype.html = function() {
          return this._elementIds.length === 0 ? '' : __crispyDomInnerHtml(this._docId, this._elementIds[0]);
        };
        CheerioWrapper.prototype.attr = function(name) {
          if (this._elementIds.length === 0) return undefined;
          var val = __crispyDomAttr(this._docId, this._elementIds[0], name);
          return val === '__UNDEFINED__' ? undefined : val;
        };
        CheerioWrapper.prototype.first = function() { return __wrap(this._docId, this._elementIds.slice(0, 1)); };
        CheerioWrapper.prototype.last = function() { return __wrap(this._docId, this._elementIds.slice(-1)); };
        CheerioWrapper.prototype.eq = function(index) {
          return index >= 0 && index < this._elementIds.length ? __wrap(this._docId, [this._elementIds[index]]) : __wrap(this._docId, []);
        };
        CheerioWrapper.prototype.each = function(callback) {
          for (var i = 0; i < this._elementIds.length; i++) callback.call(__wrap(this._docId, [this._elementIds[i]]), i, __wrap(this._docId, [this._elementIds[i]]));
          return this;
        };
        CheerioWrapper.prototype.map = function(callback) {
          var results = [];
          for (var i = 0; i < this._elementIds.length; i++) {
            var el = __wrap(this._docId, [this._elementIds[i]]);
            var result = callback.call(el, i, el);
            if (result != null) results.push(result);
          }
          return { length: results.length, get: function(i) { return results[i]; }, toArray: function() { return results; } };
        };
        CheerioWrapper.prototype.next = function() {
          var ids = this._elementIds.map(function(id) { return __crispyDomNext(this._docId, id); }, this);
          return __wrap(this._docId, ids.filter(function(id) { return id !== '__NONE__'; }));
        };
        CheerioWrapper.prototype.prev = function() {
          var docId = this._docId;
          var ids = this._elementIds.map(function(id) { return __crispyDomPrev(docId, id); });
          return __wrap(docId, ids.filter(function(id) { return id !== '__NONE__'; }));
        };
        CheerioWrapper.prototype.toArray = function() {
          var docId = this._docId;
          return this._elementIds.map(function(id) { return __wrap(docId, [id]); });
        };

        var cheerio = {
          load: function(html) {
            var docId = __crispyDomLoad(String(html));
            return function(selector) {
              if (selector === undefined) return __wrap(docId, []);
              return __wrap(docId, JSON.parse(__crispyDomSelect(docId, selector)));
            };
          }
        };
        globalThis.cheerio = cheerio;
    """.trimIndent()

    private fun storagePolyfill() = """
        globalThis.crispy = {
          storage: {
            get: function(key) { return __crispyStorageGet(String(key)); },
            set: function(key, value) { __crispyStorageSet(String(key), String(value)); }
          }
        };
    """.trimIndent()

    private fun requirePolyfill() = """
        globalThis.require = function(moduleName) {
          if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native') return cheerio;
          if (moduleName === 'crypto-js') return CryptoJS;
          throw new Error("Module '" + moduleName + "' is not available");
        };
    """.trimIndent()
}
