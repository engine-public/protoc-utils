package com.engine.protoc.util.recorder

import com.google.protobuf.ByteString
import com.google.protobuf.compiler.PluginProtos

public fun main() {
    PluginProtos
        .CodeGeneratorResponse
        .newBuilder()
        /* protoc fails compilation if a plugin does not support all features in the current fileset.
         * since this plugin's job is to pass _all_ information directly to disk, we should always support all features.
         * note: this does not use "options" to control which features to support for 2 reasons:
         * 1. using an option would require this plugin to parse the input
         * 2. we want customers of the plugin to be able to set their options to be passed to their plugins, and using our own options interferes with that goal.
         */
        .setSupportedFeatures(
            PluginProtos.CodeGeneratorResponse.Feature.entries.fold(0L) { acc, feature ->
                acc.or(feature.number.toLong())
            },
        )
        .addFile(
            PluginProtos.CodeGeneratorResponse.File.newBuilder().apply {
                name = "code-generator-request.binpb"
                contentBytes = ByteString.copyFrom(System.`in`.readBytes())
            },
        )
        .build()
        .writeTo(System.out)
}
