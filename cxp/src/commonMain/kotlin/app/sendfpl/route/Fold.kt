package app.sendfpl.route

/**
 * Each letter outside ASCII whose canonical decomposition, Unicode's NFD, holds an ASCII letter,
 * keyed by that letter: what [RouteImporter]'s fold keeps of it. Generated from the JVM's
 * java.text.Normalizer, and FoldTableTest checks it against that over every code point.
 */
internal val FOLD: Map<Char, Char> = listOf(
    'A' to "ÀÁÂÃÄÅàáâãäåĀāĂăĄąǍǎǞǟǠǡǺǻȀȁȂȃȦȧḀḁẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặÅ",
    'B' to "ḂḃḄḅḆḇ",
    'C' to "ÇçĆćĈĉĊċČčḈḉ",
    'D' to "ĎďḊḋḌḍḎḏḐḑḒḓ",
    'E' to "ÈÉÊËèéêëĒēĔĕĖėĘęĚěȄȅȆȇȨȩḔḕḖḗḘḙḚḛḜḝẸẹẺẻẼẽẾếỀềỂểỄễỆệ",
    'F' to "Ḟḟ",
    'G' to "ĜĝĞğĠġĢģǦǧǴǵḠḡ",
    'H' to "ĤĥȞȟḢḣḤḥḦḧḨḩḪḫẖ",
    'I' to "ÌÍÎÏìíîïĨĩĪīĬĭĮįİǏǐȈȉȊȋḬḭḮḯỈỉỊị",
    'J' to "Ĵĵǰ",
    'K' to "ĶķǨǩḰḱḲḳḴḵK",
    'L' to "ĹĺĻļĽľḶḷḸḹḺḻḼḽ",
    'M' to "ḾḿṀṁṂṃ",
    'N' to "ÑñŃńŅņŇňǸǹṄṅṆṇṈṉṊṋ",
    'O' to "ÒÓÔÕÖòóôõöŌōŎŏŐőƠơǑǒǪǫǬǭȌȍȎȏȪȫȬȭȮȯȰȱṌṍṎṏṐṑṒṓỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợ",
    'P' to "ṔṕṖṗ",
    'R' to "ŔŕŖŗŘřȐȑȒȓṘṙṚṛṜṝṞṟ",
    'S' to "ŚśŜŝŞşŠšȘșṠṡṢṣṤṥṦṧṨṩ",
    'T' to "ŢţŤťȚțṪṫṬṭṮṯṰṱẗ",
    'U' to "ÙÚÛÜùúûüŨũŪūŬŭŮůŰűŲųƯưǓǔǕǖǗǘǙǚǛǜȔȕȖȗṲṳṴṵṶṷṸṹṺṻỤụỦủỨứỪừỬửỮữỰự",
    'V' to "ṼṽṾṿ",
    'W' to "ŴŵẀẁẂẃẄẅẆẇẈẉẘ",
    'X' to "ẊẋẌẍ",
    'Y' to "ÝýÿŶŷŸȲȳẎẏẙỲỳỴỵỶỷỸỹ",
    'Z' to "ŹźŻżŽžẐẑẒẓẔẕ",
).flatMap { (ascii, letters) -> letters.map { it to ascii } }.toMap()
