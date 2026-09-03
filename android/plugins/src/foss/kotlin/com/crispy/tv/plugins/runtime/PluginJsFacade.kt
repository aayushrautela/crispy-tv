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
            ${languagePolyfill()}
        """.trimIndent()
    }

    private fun fetchPolyfill() = """
        function __normalizeFetchHeaders(headers) {
          var out = {};
          if (!headers) return out;
          if (typeof headers.forEach === 'function') {
            headers.forEach(function(value, key) { out[key] = String(value); });
            return out;
          }
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
          var followRedirects = options.redirect !== 'manual';
          var requestJson = JSON.stringify({ url: url, method: method, headers: headers, body: body, followRedirects: followRedirects });
          var parsed = JSON.parse(await __crispyFetch(requestJson));
          return {
            ok: parsed.status >= 200 && parsed.status < 300,
            status: parsed.status,
            statusText: parsed.statusText || '',
            url: parsed.url || url,
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
        function __toUint8Array(data) {
          if (!data) return new Uint8Array(0);
          if (data instanceof Uint8Array) return data;
          if (data instanceof ArrayBuffer) return new Uint8Array(data);
          if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(data)) {
            return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
          }
          if (Array.isArray(data)) return new Uint8Array(data);
          if (typeof data.length === 'number') return new Uint8Array(Array.prototype.slice.call(data));
          return new Uint8Array(0);
        }
        function __copyUint8Array(bytes) {
          bytes = __toUint8Array(bytes);
          var copy = new Uint8Array(bytes.length);
          copy.set(bytes);
          return copy;
        }
        function __bytesToArrayBuffer(bytes) { return __copyUint8Array(bytes).buffer; }
        function __concatBytes() {
          var total = 0, parts = [];
          for (var i = 0; i < arguments.length; i++) { var part = __toUint8Array(arguments[i]); parts.push(part); total += part.length; }
          var out = new Uint8Array(total), offset = 0;
          for (var j = 0; j < parts.length; j++) { out.set(parts[j], offset); offset += parts[j].length; }
          return out;
        }
        function __utf8Bytes(str) { return __hexToBytes(__crispyUtf8ToHex(String(str))); }
        function __bytesToUtf8(bytes) { return __crispyBytesToUtf8(__bytesToHex(__toUint8Array(bytes))); }
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
          if (name.indexOf('PBKDF2') >= 0) return 'PBKDF2';
          if (name.indexOf('HMAC') >= 0) return 'HMAC';
          return name;
        }
        function __aesModeName(mode, padding) {
          var normalized = __normalizeAlgorithmName(mode || 'AES-CBC');
          if (padding === 'NoPadding' || (padding && padding.NoPadding)) normalized += '-NoPadding';
          return normalized;
        }
        function __nativeDigestBytes(hash, dataBytes) { return __hexToBytes(__crispyDigestHex(__normalizeHashName(hash), __bytesToHex(__toUint8Array(dataBytes)))); }
        function __nativeHmacBytes(hash, keyBytes, dataBytes) { return __hexToBytes(__crispyHmacHex(__normalizeHashName(hash), __bytesToHex(__toUint8Array(keyBytes)), __bytesToHex(__toUint8Array(dataBytes)))); }
        function __nativePbkdf2Bytes(passwordBytes, saltBytes, iterations, keySizeBits, hash) {
          return __hexToBytes(__crispyPbkdf2Hex(__bytesToHex(__toUint8Array(passwordBytes)), __bytesToHex(__toUint8Array(saltBytes)), iterations, keySizeBits, __normalizeHashName(hash)));
        }
        function __nativeAesBytes(encrypt, mode, keyBytes, ivBytes, dataBytes) {
          var fn = encrypt ? __crispyAesEncryptHex : __crispyAesDecryptHex;
          return __hexToBytes(fn(mode, __bytesToHex(__toUint8Array(keyBytes)), __bytesToHex(__toUint8Array(ivBytes)), __bytesToHex(__toUint8Array(dataBytes))));
        }
        function __evpKdf(passwordBytes, saltBytes, keySizeBytes, ivSizeBytes) {
          var targetSize = keySizeBytes + ivSizeBytes;
          var derived = new Uint8Array(targetSize);
          var block = new Uint8Array(0);
          var offset = 0;
          while (offset < targetSize) {
            block = __nativeDigestBytes('MD5', __concatBytes(block, passwordBytes, saltBytes || new Uint8Array(0)));
            var take = Math.min(block.length, targetSize - offset);
            derived.set(block.subarray(0, take), offset);
            offset += take;
          }
          return { key: derived.subarray(0, keySizeBytes), iv: derived.subarray(keySizeBytes, keySizeBytes + ivSizeBytes) };
        }
        function __opensslSaltHeader() { return new Uint8Array([83, 97, 108, 116, 101, 100, 95, 95]); }
        function __hasOpenSslSaltHeader(bytes) {
          var header = __opensslSaltHeader();
          if (!bytes || bytes.length < 16) return false;
          for (var i = 0; i < header.length; i++) { if (bytes[i] !== header[i]) return false; }
          return true;
        }
        function __makeCipherParams(ciphertext, key, iv, salt, mode) {
          return {
            ciphertext: __bytesToWordArray(ciphertext),
            key: key ? __bytesToWordArray(key) : undefined,
            iv: iv ? __bytesToWordArray(iv) : undefined,
            salt: salt ? __bytesToWordArray(salt) : undefined,
            mode: mode,
            toString: function(formatter) { return (formatter || CryptoJS.format.OpenSSL).stringify(this); }
          };
        }

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
          var merged = __concatBytes(__wordArrayToBytes(this), __wordArrayToBytes(wordArray));
          this.words = __bytesToWords(merged);
          this.sigBytes = merged.length;
          return this;
        };
        WordArray.prototype.clone = function() { return __wordArrayCreate(this.words.slice(0), this.sigBytes); };
        function __bytesToWords(bytes) {
          bytes = __toUint8Array(bytes);
          var words = [];
          for (var i = 0; i < bytes.length; i++) words[i >>> 2] |= (bytes[i] & 0xff) << (24 - (i % 4) * 8);
          return words;
        }
        function __wordArrayToBytes(wordArray) {
          if (!__isWordArray(wordArray)) return typeof wordArray === 'string' ? __utf8Bytes(wordArray) : __toUint8Array(wordArray);
          var bytes = new Uint8Array(wordArray.sigBytes);
          for (var i = 0; i < wordArray.sigBytes; i++) bytes[i] = (wordArray.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
          return bytes;
        }
        function __wordArrayCreate(words, sigBytes) { return new WordArray(words, sigBytes); }
        function __isWordArray(value) { return value && typeof value === 'object' && Array.isArray(value.words) && typeof value.sigBytes === 'number'; }
        function __normalizeWordArrayInput(value) {
          if (__isWordArray(value)) return __wordArrayToBytes(value);
          if (typeof value === 'string') return __utf8Bytes(value);
          return __toUint8Array(value);
        }
        function __bytesToWordArray(bytes) { bytes = __toUint8Array(bytes); return __wordArrayCreate(__bytesToWords(bytes), bytes.length); }

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
              parse: function(s) { return __bytesToWordArray(__hexToBytes(__crispyBase64DecodeHex(String(s || '')))); }
            },
            Base64url: {
              stringify: function(wa) {
                var s = CryptoJS.enc.Base64.stringify(wa).replace(/\+/g, '-').replace(/\//g, '_');
                while (s.length > 0 && s.charAt(s.length - 1) === '=') s = s.substring(0, s.length - 1);
                return s;
              },
              parse: function(s) {
                s = String(s || '').replace(/-/g, '+').replace(/_/g, '/');
                while (s.length % 4) s += '=';
                return CryptoJS.enc.Base64.parse(s);
              }
            }
          },
          lib: {
            WordArray: {
              create: function(words, sigBytes) {
                if (words == null) return __wordArrayCreate([], sigBytes || 0);
                if (__isWordArray(words)) return words.clone();
                if (typeof words === 'string') return CryptoJS.enc.Utf8.parse(words);
                if (typeof ArrayBuffer !== 'undefined' && (words instanceof ArrayBuffer || (ArrayBuffer.isView && ArrayBuffer.isView(words)))) {
                  var bytes = __toUint8Array(words);
                  return __bytesToWordArray(sigBytes != undefined ? bytes.subarray(0, sigBytes) : bytes);
                }
                return __wordArrayCreate(words, sigBytes);
              },
              random: function(nBytes) {
                var bytes = new Uint8Array(nBytes || 0);
                globalThis.crypto.getRandomValues(bytes);
                return __bytesToWordArray(bytes);
              }
            },
            CipherParams: {
              create: function(params) {
                params = params || {};
                params.toString = params.toString || function(formatter) { return (formatter || CryptoJS.format.OpenSSL).stringify(this); };
                return params;
              }
            }
          },
          format: {
            OpenSSL: {
              stringify: function(cipherParams) {
                var cipherBytes = __wordArrayToBytes(cipherParams.ciphertext);
                var out = cipherParams.salt
                  ? __concatBytes(__opensslSaltHeader(), __wordArrayToBytes(cipherParams.salt), cipherBytes)
                  : cipherBytes;
                return CryptoJS.enc.Base64.stringify(__bytesToWordArray(out));
              },
              parse: function(str) {
                var bytes = __wordArrayToBytes(CryptoJS.enc.Base64.parse(str));
                if (__hasOpenSslSaltHeader(bytes)) {
                  return CryptoJS.lib.CipherParams.create({
                    salt: __bytesToWordArray(bytes.subarray(8, 16)),
                    ciphertext: __bytesToWordArray(bytes.subarray(16))
                  });
                }
                return CryptoJS.lib.CipherParams.create({ ciphertext: __bytesToWordArray(bytes) });
              }
            }
          },
          mode: { CBC: 'AES-CBC', GCM: 'AES-GCM', ECB: 'AES-ECB' },
          pad: { Pkcs7: 'Pkcs7', NoPadding: 'NoPadding' },
          algo: { MD5: 'MD5', SHA1: 'SHA1', SHA256: 'SHA256', SHA384: 'SHA384', SHA512: 'SHA512', AES: 'AES' },
          MD5: function(m) { return __bytesToWordArray(__nativeDigestBytes('MD5', __normalizeWordArrayInput(m))); },
          SHA1: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA1', __normalizeWordArrayInput(m))); },
          SHA256: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA256', __normalizeWordArrayInput(m))); },
          SHA384: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA384', __normalizeWordArrayInput(m))); },
          SHA512: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA512', __normalizeWordArrayInput(m))); },
          HmacMD5: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('MD5', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
          HmacSHA1: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA1', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
          HmacSHA256: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA256', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
          HmacSHA384: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA384', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
          HmacSHA512: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA512', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
          PBKDF2: function(pass, salt, options) {
            options = options || {};
            var pBytes = __normalizeWordArrayInput(pass);
            var sBytes = __normalizeWordArrayInput(salt);
            var iter = options.iterations || 1000;
            var kSize = options.keySize || 8;
            var algo = options.hasher || 'SHA1';
            return __bytesToWordArray(__nativePbkdf2Bytes(pBytes, sBytes, iter, kSize * 32, algo));
          },
          AES: {
            encrypt: function(message, key, options) {
              options = options || {};
              var data = __normalizeWordArrayInput(message);
              var kBytes, ivBytes, saltBytes;
              var isPassphrase = typeof key === 'string';
              if (isPassphrase) {
                saltBytes = options.salt ? __wordArrayToBytes(options.salt) : __wordArrayToBytes(CryptoJS.lib.WordArray.random(8));
                var derived = __evpKdf(__utf8Bytes(key), saltBytes, 32, 16);
                kBytes = derived.key;
                ivBytes = options.iv ? __wordArrayToBytes(options.iv) : derived.iv;
              } else {
                kBytes = __wordArrayToBytes(key);
                ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
              }
              var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
              var resBytes = __nativeAesBytes(true, mode, kBytes, ivBytes, data);
              return __makeCipherParams(resBytes, kBytes, ivBytes, saltBytes, mode);
            },
            decrypt: function(cipher, key, options) {
              options = options || {};
              var cipherParams = typeof cipher === 'string' ? CryptoJS.format.OpenSSL.parse(cipher) : cipher;
              var data = cipherParams.ciphertext ? __wordArrayToBytes(cipherParams.ciphertext) : __toUint8Array(cipherParams);
              var kBytes, ivBytes;
              var isPassphrase = typeof key === 'string';
              if (isPassphrase) {
                var saltBytes = options.salt ? __wordArrayToBytes(options.salt) : (cipherParams.salt ? __wordArrayToBytes(cipherParams.salt) : new Uint8Array(0));
                var derived = __evpKdf(__utf8Bytes(key), saltBytes, 32, 16);
                kBytes = derived.key;
                ivBytes = options.iv ? __wordArrayToBytes(options.iv) : derived.iv;
              } else {
                kBytes = __wordArrayToBytes(key);
                ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
              }
              var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
              return __bytesToWordArray(__nativeAesBytes(false, mode, kBytes, ivBytes, data));
            }
          }
        };
        globalThis.CryptoJS = CryptoJS;

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
            digest: async function(algo, data) { return __bytesToArrayBuffer(__nativeDigestBytes(algo, __toUint8Array(data))); },
            importKey: async function(fmt, data, algo, extractable, usages) {
              fmt = String(fmt || 'raw').toLowerCase();
              if (fmt !== 'raw' && fmt !== 'pkcs8' && fmt !== 'spki') throw new Error('Unsupported key format: ' + fmt);
              var algorithm = (algo && algo.name) ? algo : { name: __normalizeAlgorithmName(algo) };
              var type = fmt === 'spki' ? 'public' : (fmt === 'pkcs8' ? 'private' : 'secret');
              return { type: type, extractable: !!extractable, algorithm: algorithm, usages: usages || [], _raw: __copyUint8Array(__toUint8Array(data)) };
            },
            exportKey: async function(fmt, key) {
              fmt = String(fmt || 'raw').toLowerCase();
              if (fmt !== 'raw' && fmt !== 'pkcs8' && fmt !== 'spki') throw new Error('Unsupported key format: ' + fmt);
              return __bytesToArrayBuffer(key._raw);
            },
            generateKey: async function(algo, extractable, usages) {
              var name = __normalizeAlgorithmName((algo && algo.name) || algo);
              if (name !== 'AES-CBC' && name !== 'AES-GCM' && name !== 'HMAC') throw new Error('Unsupported generateKey algorithm: ' + name);
              var length = (algo && algo.length) || 256;
              var bytes = new Uint8Array(length / 8);
              globalThis.crypto.getRandomValues(bytes);
              return { type: 'secret', extractable: !!extractable, algorithm: (algo && algo.name) ? algo : { name: name }, usages: usages || [], _raw: bytes };
            },
            deriveBits: async function(params, key, len) {
              if (__normalizeAlgorithmName(params) !== 'PBKDF2') throw new Error('Only PBKDF2 deriveBits is supported');
              var pBytes = __toUint8Array(key._raw);
              var sBytes = __toUint8Array(params.salt);
              var hash = params.hash || 'SHA-256';
              return __bytesToArrayBuffer(__nativePbkdf2Bytes(pBytes, sBytes, params.iterations || 1000, len, hash));
            },
            deriveKey: async function(params, key, derivedKeyAlgo, extractable, usages) {
              var length = (derivedKeyAlgo && derivedKeyAlgo.length) || 256;
              var raw = await globalThis.crypto.subtle.deriveBits(params, key, length);
              var algorithm = (derivedKeyAlgo && derivedKeyAlgo.name) ? derivedKeyAlgo : { name: __normalizeAlgorithmName(derivedKeyAlgo) };
              return { type: 'secret', extractable: !!extractable, algorithm: algorithm, usages: usages || [], _raw: new Uint8Array(raw) };
            },
            encrypt: async function(params, key, data) {
              var mode = __normalizeAlgorithmName(params);
              if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported encrypt algorithm: ' + mode);
              return __bytesToArrayBuffer(__nativeAesBytes(true, mode, __toUint8Array(key._raw), __toUint8Array((params && params.iv) || []), __toUint8Array(data)));
            },
            decrypt: async function(params, key, data) {
              var mode = __normalizeAlgorithmName(params);
              if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported decrypt algorithm: ' + mode);
              return __bytesToArrayBuffer(__nativeAesBytes(false, mode, __toUint8Array(key._raw), __toUint8Array((params && params.iv) || []), __toUint8Array(data)));
            },
            sign: async function(algo, key, data) {
              var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
              return __bytesToArrayBuffer(__nativeHmacBytes(hash, __toUint8Array(key._raw), __toUint8Array(data)));
            },
            verify: async function(algo, key, sig, data) {
              var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
              var expected = __nativeHmacBytes(hash, __toUint8Array(key._raw), __toUint8Array(data));
              var actual = __toUint8Array(sig);
              if (expected.length !== actual.length) return false;
              var diff = 0;
              for (var i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
              return diff === 0;
            }
          }
        };

        globalThis.WebAssembly = globalThis.WebAssembly || {
          instantiate: async function() {
            console.warn('WebAssembly.instantiate called (not supported in plugins)');
            return { instance: { exports: {} }, module: {} };
          }
        };
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
        CheerioWrapper.prototype.get = function(index) {
          if (typeof index === 'number') {
            if (index >= 0 && index < this._elementIds.length) return __wrap(this._docId, [this._elementIds[index]]);
            return undefined;
          }
          return this.toArray();
        };
        CheerioWrapper.prototype.filter = function(selectorOrCallback) {
          if (typeof selectorOrCallback !== 'function') return this;
          var kept = [];
          for (var i = 0; i < this._elementIds.length; i++) {
            var el = __wrap(this._docId, [this._elementIds[i]]);
            if (selectorOrCallback.call(el, i, el)) kept.push(this._elementIds[i]);
          }
          return __wrap(this._docId, kept);
        };
        CheerioWrapper.prototype.children = function(sel) { return this.find(sel || '*'); };
        CheerioWrapper.prototype.parent = function() { return __wrap(this._docId, []); };
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
            var selectAll = function(selector) {
              if (selector === undefined) return __wrap(docId, []);
              return __wrap(docId, JSON.parse(__crispyDomSelect(docId, selector)));
            };
            var findIn = function(contextIds, selector) {
              var all = [];
              for (var i = 0; i < contextIds.length; i++) {
                all = all.concat(JSON.parse(__crispyDomFind(docId, contextIds[i], selector)));
              }
              return __wrap(docId, all);
            };
            var root = function(selector, context) {
              if (selector && selector._elementIds) return selector;
              if (context && context._elementIds && context._elementIds.length > 0) {
                return findIn(context._elementIds, selector);
              }
              return selectAll(selector);
            };
            root.html = function(el) {
              if (el && el._elementIds && el._elementIds.length > 0) {
                return __crispyDomHtml(docId, el._elementIds[0]);
              }
              return __crispyDomHtml(docId, '');
            };
            return root;
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
          if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') return cheerio;
          if (moduleName === 'crypto-js') return CryptoJS;
          throw new Error("Module '" + moduleName + "' is not available");
        };
    """.trimIndent()

    private fun languagePolyfill() = """
        if (!Array.prototype.flat) {
          Array.prototype.flat = function(depth) {
            depth = depth === undefined ? 1 : Math.floor(depth);
            if (depth < 1) return Array.prototype.slice.call(this);
            return (function flatten(arr, d) {
              return d > 0
                ? arr.reduce(function(acc, val) { return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val); }, [])
                : arr.slice();
            })(this, depth);
          };
        }
        if (!Array.prototype.flatMap) {
          Array.prototype.flatMap = function(callback, thisArg) { return this.map(callback, thisArg).flat(); };
        }
        if (!Object.entries) {
          Object.entries = function(obj) {
            var result = [];
            for (var key in obj) { if (obj.hasOwnProperty(key)) result.push([key, obj[key]]); }
            return result;
          };
        }
        if (!Object.fromEntries) {
          Object.fromEntries = function(entries) {
            var result = {};
            for (var i = 0; i < entries.length; i++) result[entries[i][0]] = entries[i][1];
            return result;
          };
        }
        if (!String.prototype.replaceAll) {
          String.prototype.replaceAll = function(search, replace) { return this.split(search).join(replace); };
        }
    """.trimIndent()
}
