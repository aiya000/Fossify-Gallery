# smbj のストリームを閉じても、共有側のハンドルは開いたまま

## おこったこと

`SmbStreamLoader` がサムネのために毎回 `SmbClient.open(context, path).inputStream()` を呼び、
返ってきた `InputStream` だけを閉じていました。

`SmbClient.OpenFile` は捨てられ、共有が持っているファイルハンドルは開いたままです。

## 原因

smbj 0.14.0 の `com.hierynomus.smbj.share.FileInputStream.close()` は、
自分の `file` フィールドとバッファに `null` を入れるだけです。

```
public void close();
  0: aload_0
  1: iconst_1
  2: putfield  isClosed
  5: aload_0
  6: aconst_null
  7: putfield  file          <- 参照を捨てるだけ
 10: aload_0
 11: aconst_null
 12: putfield  buf
 15: return
```

`File.close()` を呼ばないので、サーバ側の close は送られません。

## したこと

`OpenFile` を持っておいて、`DataFetcher.cleanup()` でストリームと一緒に閉じるようにしました。

## 次に気をつけること

- **`SmbClient.open()` は `.use { }` で閉じてください**
    - `use` が書けない場面（ストリームを呼び出し元に返すなど）では、
      `OpenFile` を持ち回して、あとで必ず閉じます
- 同じ理由で、`inputStream()` を返す関数は「誰が `OpenFile` を閉じるのか」を
  コメントに書いておくと、次に読む人が迷わないのです

## 関係する場所

- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbClient.kt`
- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbStreamLoader.kt`
