package org.fossify.gallery.helpers

// Where a pCloud request goes: the host that came with the token, and the method or path under
// it. pCloud's own hosts are bare names -- api.pcloud.com for the US region, eapi.pcloud.com for
// the european one, and whichever content host a link answer named -- and all of them are reached
// over https, which is the scheme a bare host is given here.
//
// A host that carries a scheme of its own is taken as it comes, port and all. Nothing pCloud
// hands out looks like that, so the real service always lands on the https branch; what it is
// for is the stub of test-device/, which stands in for the whole API so that a transfer bound for
// pCloud can be driven on an emulator. The alternative was to serve the stub over https, which
// would mean a certificate the app is built to trust -- and a debug build that trusts a
// certificate kept in this repository is a worse thing to carry around than a debug build that
// can be pointed at a plain http host
fun pCloudUrl(host: String, path: String): String {
    val base = (if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host").trimEnd('/')
    return if (path.startsWith("/")) "$base$path" else "$base/$path"
}
