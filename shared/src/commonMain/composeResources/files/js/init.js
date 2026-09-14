async (_java) => {
  class FormData {
    constructor(data) {
      const _data = data || {}
      this.__js_proto__ = "FormData";
      this.__items__ = Object.keys(_data).map((k) => ({
        name: k,
        value: _data[k].filename ? _data[k].value : _data[k],
        filename: _data[k].filename
      }));
    }

    append(name, value, filename) {
      // __items__ 是普通数组，没有 append（那是 FormData 自己的方法）。
      this.__items__.push({ name, value, filename })
    }

    delete(name) {
      const index = this.__items__.findIndex((v) => v.name === name);
      if (index < 0) return;
      this.__items__.splice(index, 1);
    }

    entries() {
      const items = this.__items__;
      return (function* () {
        for (const item of items)
          yield [item.name, item.value, item.filename];
      })();
    }

    get(name) {
      const found = this.__items__.find((v) => v.name === name);
      return found ? found.value : null;
    }

    getAll(name) {
      return this.__items__
        .filter((v) => v.name === name)
        .map((v) => v.value);
    }

    has(name) {
      return this.__items__.some((v) => v.name === name);
    }

    keys() {
      const items = this.__items__;
      return (function* () {
        for (const item of items)
          yield item.name;
      })();
    }

    set(name, value, filename) {
      const index = this.__items__.findIndex((v) => v.name === name);
      if (index < 0) {
        this.append(name, value, filename);
        return;
      }
      // 原来写的是 this.splice(...)，但 splice 在 __items__ 上，不在 FormData 上
      this.__items__.splice(index, 1, { name, value, filename });
    }

    values() {
      const items = this.__items__;
      return (function* () {
        for (const item of items)
          yield item.value;
      })();
    }

    forEach(callback, thisArg) {
      for (const [name, value] of this.entries())
        callback.call(thisArg, value, name, this);
    }

    [Symbol.iterator]() {
      return this.entries();
    }
  };

  class _TextEncoder {
    constructor(encoding, options) {
      this.encoding = "" + (encoding || "utf-8");
    }
    encode(data) {
      return _java(null, 'encode', data, this.encoding)
    }
  }

  class _TextDecoder {
    constructor(encoding, options) {
      this.encoding = "" + (encoding || "utf-8");
    }
    decode(data) {
      return _java(null, 'decode', data, this.encoding)
    }
  }

  class Response {
    constructor(response) {
      response = response || {};
      this.headers = response.headers;
      this.ok = response.ok;
      this.redirected = response.redirected;
      this.status = response.status;
      this.url = response.url;
      this.body = response.body;
      // TODO `type`, `useFinalURL`, `bodyUsed`
    }

    clone() {
      return new Response(this)
    }

    async arrayBuffer() {
      return this.body;
    }

    async text(utfLabel) {
      return new _TextDecoder(utfLabel || "utf-8").decode(this.body);
    }

    async json(utfLabel) {
      return JSON.parse(await this.text(utfLabel));
    }
  }

  class Request {
    constructor(input, init) {
      const options = init || {};
      const request = typeof input === "string" ? { url: input } : input;
      this.url = request.url;
      this.method = options.method || request.method || "GET";
      this.headers = options.headers || request.headers || {};
      this.body = options.body || request.body;
      this.redirect = options.redirect || request.redirect || "follow";
      // TODO `mode`, `credentials`, `cache`, `referrer`, `referrerPolicy`, `integrity`, `redirect`
    }
  }

  // toString(16) 不补零：0x0a 会得到 "a"，URL 编码必须写成 "%0a"。
  const encodeURI_hex = (encoder, c) => [...new Uint8Array(encoder.encode(c))]
    .map(v => "%" + v.toString(16).padStart(2, "0"))
    .join("").toUpperCase();

  const globalProperties = {
    TextEncoder: _TextEncoder,
    TextDecoder: _TextDecoder,
    Request,
    Response,
    FormData,
    console: {
      log: (...args) => {
        _java(null, "console", "log", args);
      },
      info: (...args) => {
        _java(null, "console", "info", args);
      },
      debug: (...args) => {
        _java(null, "console", "debug", args);
      },
      error: (...args) => {
        _java(null, "console", "error", args);
      }
    },
    fetch: async (input, init) => {
      const response = await _java(null, "fetchAsync", new Request(input, init));
      return new Response(response);
    },
    encodeURI: (uri, encoding) => {
      const encoder = new _TextEncoder(encoding || "utf-8");
      return `${uri}`.replace(/[^a-zA-Z0-9-_.!~*'();/?:@&=+$,#]/g, (c) => encodeURI_hex(encoder, c));
    },
    encodeURIComponent: (uri, encoding) => {
      const encoder = new _TextEncoder(encoding || "utf-8");
      return `${uri}`.replace(/[^a-zA-Z0-9-_.!~*'()]/g, (c) => encodeURI_hex(encoder, c));
    }
  }

  Object.defineProperties(this, Object.assign({},
    ...Object.keys(globalProperties).map((key) => ({
      [key]: {
        value: globalProperties[key],
        writable: false
      }
    }))));
};
