package io.github.ilevs.p2pmusic

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Points [Texts] at the English strings.xml, the way the app points it at its resources. */
fun useEnglishTexts() {
    val nodes =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
            .getElementsByTagName("string")
    val strings =
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    Texts.lookup = { id, args -> String.format(Locale.ROOT, strings.getValue(names.getValue(id)), *args) }
}
