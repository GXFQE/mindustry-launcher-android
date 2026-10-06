package io.mdt.launcher;

/**
 * 自动生成的「方块名 → 配色 / 占地格数」表（勿手改）。
 * 生成器：MDT-Android-Dev/_lab/msch/GenBlockTable.java
 *        （工作副本：MDT-Android/.tmp-prev-lab/GenBlockTable.java，两份一样）
 * 生成于 2026-10-06；447 行（有配色 424，没有 23）。
 * 列（Tab 分隔）：name argb size
 *   · argb = 8 位十六进制 ARGB（alpha 一律 FF —— 游戏 loadColors() 之后就是纯色）；
 *     `-` = 这个方块**没有配色**（空气 / 建造占位 build1..16 / 遗留方块）。
 *   · size = 占地格数（游戏 `Block.size`）—— 多格方块要靠它才知道占几格。
 * 编码：UTF-8 文本 → Deflater 压缩 → Base64（标准字母表，无换行）；
 *   产品侧用 {@link MapStats#inflate} 解（同一套编码，不另写解码器）。
 *
 * <h3>两个来源</h3>
 * 名字 + 尺寸 = 桌面 <code>Mindustry-160.4.jar</code> 里 `content.blocks()` 的**顺序**；
 * 颜色       = **同一版本** Android 包 <code>[Android][v160]Mindustry.apk</code> 的 `sprites/block_colors.png` 第 i 列
 *              （游戏 `ContentLoader.loadColors()` 就是拿 `pixmap.get(i,0)` 喂给第 i 个方块）。
 * 🔴 **对齐证据**（生成器当场算的）：矿类方块的颜色来自**物品**（jar 自己就带），
 *   而调色板在那些列上**恰好是空洞**（`loadColors` 对 `color == 0` 直接 continue）
 *   ⇒ 「空洞位置 == jar 自带颜色的位置」= 13/13 全部命中（v146 同样成立）。
 *
 * 🔴 **为什么烘颜色、不烘 content id**：`.msch` 里存的是**名字**，而调色板按 **id** 索引；
 *   但 id **会在版本之间漂**——实测 152.2 → 160.4 在**中间插进**方块（`ore-wall-tungsten`），
 *   之后 265 处全部错位；而我们把 v146~v160 + MindustryX 都当一等公民。
 *   颜色则几乎不随版本变：同名的 412 个方块里只有 5 个不同，每个通道只差 1~4。
 *
 * ⚠️ 模组方块（名字带模组前缀）**不在表里** ⇒ 调用方按「认不出」处理（画成中性色），
 *   这与蓝图页那句「（可能认错）」是同一条纪律：不确定就**别装成确定**。
 */
final class BlockTable {
    private BlockTable() {}

    /** 表本体（见类注释的列定义与编码） */
    static final String DATA_B64 =
            "eNp9WtuWo7qufe76l4wRLr59jm1Mhb0g5ECo6uyv31OyMSZV63S/pCVbkmVdpkzbYflz+VN9rA/7ff/T99Y0olYgLGGav8Ll244j" +
            "yH0vr40/yPMSQPVt29QWVD8OfQ+C4r8guG0Yu4ol888aP+v4s8HPJv5s8bONPwV+ivhT4qeMPxV+qvhT46eOPw1+mqTiSjqu6R+s" +
            "cFdOKquksyKlVdJakdoq6a1IcZU0V6S6kh9dCA+c/BkWnKnpWqOv5KIbfDF/Z0arRKvpsE873J+hOxi2MaoGg+W8c+FfIxri2uWf" +
            "1d67nytMU7fkVubuVNVKrWy575DYKFGxIfTPSla6oit8zPMIsX55zf24DR14ojWdE+BN8/gM98s62k++SNk0dQwDTzd7FddrRYQw" +
            "PZ6vGCHP+U6stsZfEu8X0n/J9AZ/mX5jMxrf+JZEOLva8UmUqu5rcthtfi6z/4c3Nboh0yf7OdlElBoeFPv5+3GeSZ5V2si+OD9J" +
            "vNahDkQbFtLQkg7y0bQRuzbwhOAtfniSkUoroyXF8e01j5EmXGNYyE67xJMRyzSq5rCft8/bpdxUNXXtOCE+iXYj4XXrGhL+Chwo" +
            "u2dU23ZM93Zx8z3T67qWLKMPyzL4TIdLanZKokdz1uhT0VDsuLC8xrHYUxv4wvGtvNYnAnW4h4PZ1Yodn5jZp42tcQVvFl8eI/SR" +
            "OjivazQfssvCtENGVBzd9zVcSpZBYLRtWj9wIPnKBksicGkvuoSsWwh1bVzinE7fGo7FfB1f4c6XW8Mr4XBjIleqMlV/yEnkWjey" +
            "Nu9HS0zhG13L8mA7R/o6NOrNj7umvvJ1vafCTm2apmInxTjP+mWNLI4qJsv2i9pxcLpxC5FEF9NSbflc7Lpyfuq2NRz6nDLOu87R" +
            "4db7/E3/bpxy5LToXVvb2tfxn5e0xHgTeAnVK05M20oZcz7qkI0UFNQeVfzy3+h16YRredeDqMdSLXU+cWoGyuJvn9cmqnZCGpFy" +
            "MS+9tpIPGDNwJ9urrZxOdida13RVvx91JwbqKZz026FfWGGEKXJvZ3hrrLDvd77rbGXf6jKudhtFKxpzxFUit23rRfWWnTtTdrJh" +
            "H54TMe8NohPXIyb3XUq3Qv6WpHkF7JfmyKDcf13nQ3MK2MRxTjRi39ENdpq5M0TvNbqS8mgkqZV3CKsUYpnY9jo6/7ZsbuWbc06q" +
            "PYyyrxCv9pqv/jHseR9Mu19dIoaQri79W1jTs8u+b+T85xLCpQuW6nSogu3PLAoIidA3WZcftzVWZd+6urfxyN8hkISuw+VSbXhs" +
            "y8Vt642TXGjb5mhAtFt2itQ1x4Gbt7GLrdUrr6vd/oOeoy/6oNiglLVmd+xBJ0OcOcL94CAyrQtHlTg4wokYtCn8Dg6KKQrCEYGF" +
            "flyr6MvwK+Sh/4sfNb2w5Cp9K47YPDhSoOCLok0cLtet7DQVmq/BLfb+vPxc4owKril2uxHtnELJ97aKJ0yceYkh5o2X9Vs6FOYo" +
            "IaUscqHwc0A6VGUuHzwjZdu6U6ocTOeF4GyZQtkHQVTyeiZfOgtQwtGFDGtFeGPXxLiKeLKS0cQS5WLNLRgtMTqBJH9jCG48Qrc+" +
            "wZvLw97DeKliyw8Mqwo6qW4r0MWZTpob1/h3ehvhmWj7M530oj437K6CLrmEYX22h62lKwv6ypg3mv8cxrBGM21jGRWUDLIT/ckz" +
            "ZCkZ7CLgah4pSgZZCihqm3cdZCqhR8ZRJYNs1RJtU7wx1L9ZpWPj/WmVYYwK7GXeD3iNqEz/MKvisxP0de+cmuEmLOjeOXxNslHN" +
            "EVJUXtmPKFqey25BJ0nIBMkzRUFvYmg2XAn8PM4U8XtIu8p6brE7Y6/01+D6LsF06wnBY6BbRvti+IGUq39jXrg0c/Glv5Re1JF/" +
            "2VrSUSa2v7wJGUb38AA0GRlb4sYUxx3GkG4ZRoYbNYKUnM/1fn48OGc7YzrVJOoYO4b2qrf7yhUA+RGrMv7k7RywAL6q3mnP4Wnv" +
            "wzaRgM5WYRf6vM1LJPfGNn5fnoorMxqre9nu67f7J6oezctKapT2XWeqb/8ijy/t/+P9qhDQ8AHfHygCRcdKV+5LS+h6XO1lV58U" +
            "/mLsvuHyWAIDPYOJ1VYY0adtfA6ZrIOpjcO4vg5oMjS6TGFMHcFoj55fZxau2g+OEae2ugMUbD7+GUZ+VGiMNR5rMVms8QpwPxNr" +
            "ibNdcNJaWnCzmCm+g/2KnV66q+lIybZ8hkK7awGTNDTk4fYyDX9j/XfAlizrhbmJDrlznLUdZpEaABxmlGTgWTp7lu6MYSi7hgfy" +
            "4BkT6gpk3WNZN6wA62HCWaMpOBz7KEKi3aP460jZYxtxmOG/0Wu1FqbirKTmiVFhGfrtk2+uIkOwYbh7NMJdre7UVVKh2P1sF3/p" +
            "t+UeB3XVyIDkaD7CGDym6vEVFSmMUSqAbp/A8o9b4EFyvrPKXXSlEYJYM/8dOriKLvFmJ5cghpYsIArG7ltIU7HWqtdkKb0eHGTT" +
            "AkfRjniJBR2xQp4gCs2LwwKJcyEJvpsoVH9ZgEED8/q+d96SBQKNiK6fLTg7zF51q5uEp4YulIFprwqoY993cr+RhAKJxaFWRjPU" +
            "6SvF2sve5096NXndn7ew7ncqtLcyn/vMNChVvDmdzR4ndwBUEMplLk8w0jgd4yOTL6Nd2ESvmFl/7HUso3LYGKimnxh5H3zVh7f0" +
            "2yeEuq8MvxWdWXlv54IzV9IZ61ae9oKu+CGppOddiZvzeZ88QuWofx/UvIM02T5n+r6j6zrFwZ+px46uo3BDPsaGZ3ogbH4fI0i1" +
            "rzLeto4CiJtEngUF5g2KkYOadyCZW69POy63GCNWobxSXStYn8Mn8PDgI7vx8k8Lnyw7KoZVGtNW+5Frex71pFX8nnfmZDtEMBYd" +
            "H55PBTy7vtYt15AT47jtDuHgc5VL3lEG8MWAuoTh3s+LJ3B8cnWPjBIMm39ZUNxTqJFddc6uPF1ry/FQ0otb0K2jKFpvQwAUL4dR" +
            "x4wJKCA6DMDZRgCG0eqxzP/Za4GRzKk/CFwAMnyFE9uhgXELO9jdPHFlxeRpqDDxsc6brmiIXEhuGFbQEuKoahJ6X9AwWHWFaur3" +
            "x+zLc/5O1VxJ1WL3EqgslIIBkxU6VxL8jX6Wd6EeVpqjiJ1xPmOjNRdE9tzl31e0qBD3r/BimkS1ZvNy/pdMJdHFzylesFWr4ouD" +
            "XSbGieVWAtaUJv/Z7p76Q2waDUefW4aOCmUhyqtO25zfBcd5pCa/nM1LzAt0rSubPGDRQq/fmQUM13Bi5HKvtLJa89PSiorttuiK" +
            "HOl04z3Nup82vvJegfmobGwUVCeOUUJ1zBlnmwZiS1MCv0Gv64UjJ2auAUbEVXSbf3JHhav6wlGJjm7jTcdPVL5sUfCTJhuycbuc" +
            "aHVp3C5JkKxdUvRvUsw9gcmF4egswZhcHQt/Ax4r6TMnGwWEUTOw2e4DZnjE2Hw5/FDrjltSwYzaLo954EdNo7TTESoBKdwHDxDz" +
            "2KYHFwB2F93a0y6vnWxa4z3V32ECEEJUJLrujfE99797tw0sXEOC4DecMYZPYmhnlK9iBD9jhO4cq4Wl9jUO/0cYMB/USGNLBrbQ" +
            "V5bIM/bqqYQlHnLin7gleEJDiVzEvO1cGx9vcswnA9BIYtnLIb8fpsG538ppOjqSROn6XIqLIwnr3uvwT4swvsQeUKz6YZzChSrz" +
            "q6gjtRpgKffrmtJnQIOKm+3PZcl9iNGagc6DytzlPndxWnSO3wsP6gFntOu5YMcg3ctjVwF9K8bZUYatuHzT09kTRr/iVmX6g3TI" +
            "FK7msovxwqEHE6ZFac7g0GEA4JdKQLQFmPONyYpg0DPY6cxSFhHORqEDLgCOw9tmX7nGUEAvz88Tg7BqPOYMK+MLC6eoqTlfCvKB" +
            "n64osgQpd3RVAEdUNsxwDSWU9SWkdEByKAcENWB9ugAlzZVLIRN3H+OypKZYZ+o4xBtskP90g88NLfweI4m+8XDSXBG2VA39LUyc" +
            "94WHi6lBcGw3NHxhFlmH9eQLqFCKnISR7W95qIDaV/0RH/cwU5sabMHEXNHDWFEWHXoxGCO8DjxbPO5hmyxNyZkFKG2p8kAeQi+T" +
            "jdGOLEzwKJGtRfkhnMYfVS/h73PJ+iveAchDs/FXOgoG44bFz8N4Wo5mgioLBfQh6ORDGYAi6Or4czmNGOttRwQo/WwqF/I3Ng9B" +
            "nFowebIXF7++247clnedmXBMvIgUJvn4DWTVOGdYtgdf3s5BzEmkjoifhdabXTqeOQC6FCcUqP2MpmVTDfKdE8jtNrLumx/DtsYE" +
            "FocgZ9e9igpTJ+RCbyWAKh1nAvJGY8ZL661f5sc8Dis/CxhAkD/yoyxFTiMVqet/2S1+I3P01sUorKynR72nCU6da9e+F+Cv046b" +
            "/MxRjiLGOMVzYYmzVaUtjwPYeou35Q3VtJsdYqBRI6avF0B5LBNNr+N7wbidulHvCeVidmeIixZEiIEeF8bR/uXZwXpHp1q/gS8S" +
            "Egbu4dHFjl/zHnL8GhI+p/iV0XqMKzwSrtvdTgPdIwCiIXf028oVwOv4hrMMj0ccaF0CoP7lx/TltkIs2QiRA+6+40+JmNslB8v6" +
            "oDcA3tvR+AESPZZgFTcjRZiGCg8l7Y2/JACAUX2hcpnMACBRNc/XbhymBMekTCWHgGtcFRSJB9LD/Bt3tkqxStv34xAxlAHwpkmL" +
            "vj6wXdAnoqm4uVuI/xflyqLWKT6NoXJCPNUROw6fbPgVBUmD8rlQYF96zuFX9IcxZJgdloIMTzaGznW3X/R6fzDQH7n6264bnjR7" +
            "LAExCOO2vcQhyDUNIfzC9sBB7O/r8AeaxUf4+8DdxEbzq7D+j/p4BnrN+VWOxd3ZPwZR/6BD7BAOZRx/ueEzHcV+CQw8AJkMhRs1" +
            "dBwNcMLvZVtKI0OcWB5nlrRSK2q4VJzfWE7GssUCl3AWWUsI3UW+MXG3rdK70HemlvGh5rEMU/ght5UdTBVRaflOpytFSEdEjSUH" +
            "sUSzcWwwZ45SDohc0Fc7tJbMukxzt43xP5RAn8UKBs27T/c2W+OiKDsf9sU4upytOoQnZdzOOyCZxLAuzvXsFwFt1/ZC/b4sy+Lv" +
            "UE2h5TzjKIvBscnj5u+LkDdG0RHjI133FmhoED0Pwe8MXUkvYx84rRfwWJ91vjFb7OmxZzclTydCCylVcZJiEJK18KLL2HOdt8Un" +
            "9FkX6PNr5v8Dha7iNMFilIXpWEw4SVU7Oa01FUpbfcwReTV9tDXFgJHWA+1iruHSHq3MG9AjalWeLO3QDPZA5wfCwxq0Sh55n+Ql" +
            "9O9tmqjQWCBUVHPMU+O4TcN9f/TsXO0oq8fwaf3rwpH8QLO4HDSO0L1k/Qv9YtP/B/yNF6skswH+Jvr0TU+oYeH/3DdajCdRKbtH" +
            "pnr4RV0QF1myrQNKaqh+Q8hw/zzI2Acy/X+4BYDmHniWtN6H8cDi9GZjUPymsK424uWW8TL69vfw5A6kMCBwkwZYXWZ6QfH5Y8PV" +
            "SB7NxvkTSf3Gstyoby96ff2xq+WaNJEzYBGjplZ1Jn7hY7KL0xABPR4log7qZvEzGfKt0jJH/zsbxU2jSMkP+lT4g4tjag46b+9f" +
            "do2fC2txYMCD3rSmfcNEhbsagAJy1/e8xOelfExvkB4+s9Ip+Qm/ztRCEsYpfvKJjOx+DD09v1n8D4mETto=";

    /** 表里有多少个方块（自检拿它证伪「表是空的」） */
    static final int COUNT = 447;
}
