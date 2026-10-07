package io.mdt.launcher;

/**
 * **autotiler 决策表要用的方块属性**（第 121 轮，生成表，**别手改**）。
 *
 * 生成器：`.tmp-prev-lab/GenBlendTable.java`（用法：`java GenBlendTable &lt;游戏 jar&gt; &lt;本文件&gt;`）。
 *
 * 列（Tab 分隔）：`名字 outputsItems hasItems hasLiquids outputsLiquid acceptsItems noSideBlend rotatedOutput 家族号`
 * 家族号：0 不走 autotiler · 1 Conveyor · 2 ArmoredConveyor · 3 Duct · 4 ArmoredDuct ·
 * 5 Conduit · 6 ArmoredConduit · 7 StackConveyor · 9 其它 Autotiler 实现（**没搬判据**，见文件尾注释）。
 * ⚠️ `outputsItems` / `rotatedOutput` 是**调用的结果**（子类会覆盖），不是字段。
 *
 * ⚠️ rotatedOutput 求值抛异常的方块数：2（按 false 记）
 * 家族分布：{0=436, 1=2, 2=1, 3=1, 4=1, 5=2, 6=2, 7=2}
 * 没有家族 9（覆盖完整）✅
 */
final class BlendTable {
    /** zlib+Base64；解压走 {@link MapStats#inflate} */
    static final String DATA_B64 =
            "eNqNWtuS4ygMfZ75F1e1u3tmdj8H28Rmh4sXQ9LZr18J24kxEpnqly6OEJIQujlC+W9v+d/3ZRY3W6x6adxVNjehNYc5Lwuo1+py" +
            "KVa7qPTQ0svv9PIHvfxJL/+gl3/Sy7/o5b/o5b8Zdd6YdUbPllG0ZTRtGVVbRte2VHaQcoYbDJK49Aku1t0YNAhlgxwYNLF9QSL8" +
            "70XY4QVZInnBgZOwXJud03BU7+/uoqMaCgLjdJC2WbQYqVfQl+4szRzuJW1wlnB9j5I2DDgRAndiEToUy5ML3vW/S/HFaASJJENd" +
            "tHO8HUtA+fJoEwlC0atQauSnu9M1oFkNUuIujlPDb5cjAlMB3GXyWMa+wnfOMuBFeq/6OrgKu5R3JP1da3Y3ONsS4CkpK+sUzPUc" +
            "lWpmDTIshEUGhvcg7SIbHkdEEW4NPnHHS2WEQpjV+HG9V2kDdw8ktrElscwOJMVDS+bgw0WQBJW96ztkjzWC2KKjpNZHL5aFeJ/E" +
            "M1+suxWL1G3BWkMSYxCXRJQjJOghWTf/Ufe5zAiRm1aTkSXAuouEMLAwSIokNIZK0ifZlKYodtGyBcoaQ2g08zV6/+7jJLr5OIkd" +
            "Qw1NkQcUmmZ7KfTp54jDmSA9fR58sXdQwriUfqk7ETyimctfJh+7hfbhmo/NinJadAoSIRdvE15m8FI2gxRDBWeE6HVcyFwmh5uU" +
            "JcM5+qaLC5fE4DUKwjNc1ANVJaGyLJjsx6J4URy4PUcO3qIiB2+vgIO3h8Ay318BR5A9Uo5oeyesiFvq5W7vqjovbGhe0e14p6H4" +
            "WljY+W6pPldOzv2tsvgejmoM6pYykq9BDlgzCKgyCYc+0rxX0Y8q+llFf5DlazMLK3XT1sD3GvhRAz9rYFWgnzSYFGLUDErLhdDk" +
            "iL5X0Y8q+llFf1TRn1X0VxX9q4r+XbfGWx2uW6utm6vl7IUZh72IBL7XwA+iwtIO3yH9xnaUTunQHIoem0d3lV6LvONsKYompayC" +
            "zmNJRDF5O4EQ8+JXmbZk12miA4GuefBKl0DKjW6eiYiDkKaSLQILtFszw414PAgEFYRV0dDg5DyHbamG2xntCEHf0sJskb3GPrnD" +
            "SwJeCOgYZrhLrtx7cNjoZEUITpV9azN7mcr89tT066AOWHssIBQkaWysjdRrasz37ji4U6+61I3kBL+VtsUiNLrLeptw3yad7Hxx" +
            "9jwJ6G5vUlypg6MfJSvWYwbUGPUld9btk/Ud2n20yBPOuzuQj8EOJ2aGkjM80QBqtCdkUAv0hNKAdUpwq3FJ289Rg+bqP0o/h+UK" +
            "9KteXeIoi63K9lBwrPK85bsfVyZ831yit+vcK+cvteyDd/p+PhyDjAjQLc6TTGMTZ5MY20m5FO5LDWBmdI9JmI5gtZ4DfCYpAoHj" +
            "qI7DVvdgQFzGwYjycMApHifGBl/MK6oVd/FcFD5EO9v5XCCrQfJPI3E4XWLp4uz2/i6sG3GeebdhkgvpKKuRahSbDUS/32CWd1Js" +
            "Z7LWE2u08GMZmfaQTe/PUIbDIVCQPE44J8canRkxDiAnxRqISAEeELN3vUS6v31AzN6BHOhiocychVmVOesBvdzbTLGOj2qErkn1" +
            "hCl92UC1z26Pu4Ic5i5xy27MLR5RhsMa1Umjeqnsxfke+yj+wkgq5rD99bMTpB3k7mNSEjo5pmI0UJCRuQna/dm7f2TxmlM8hnQC" +
            "ddxV/hHN4EwZdJL6p+1ZcpmgRYbMSQ2OxUB9A8FvV01wN7KtxfDGy5oOu0F5wOxfbZgxyAmS6ZuXZJDkrvK+pfanX7fPEMZSZJUO" +
            "QfP27dd34U3qDQgC/O/9+z/R9phGv7Vn43k1YIqgOe/RiYWh6AqpJjmNYoHc40c7Bt8S4pkb1DmQyboYiJPQqy44zxkhWxdoRGeu" +
            "wdqJgTjRQF3VJGclpIl9OBny42FpAvxMOxpGt4f8p51n+Uk4MV6v6uQfG0Yq2D4rAM5xVpgROVoVGgg0o2s27m88xSpBMztlS/mN" +
            "hPLNqh5Kzjma+WGypz8E4e80pgxUseCDJAiKDVGFbL399uP7uoVF8SvZwMA/v2v1L9b9h6rteOSGwmb8Ms0TwJv9zWGP53jGn8+x" +
            "kCx7jSR6SC60lZ84p/qB5JWoB9KK1G1OWTdtSchbuaQlDT5jWG+sG4ia7AFVCy86MQzqzDJVKSKAXndiEJ7WuUTvTAdVD3Y5kK2Y" +
            "XgCqbw8dR4UCihdhTnjeRV4u0kOzoE5s8vzrw1g7xIEO6/Syhr0oo4/NQtZ0mln0gYU7VJC8y4TQN5UgrexvouKDGsqufoufwUuT" +
            "9ZM0KWodrujZiJ7c6Y7d7qKWivUuOn4dtMtBKx3meiVYikMYxWGaLmYBs5XRCJwE0TiwB5ensa22JbH0C5pGfgX/FCwLwzj/udId" +
            "vNLZxnyKgh/MM/vnm9NvwbB/XSYiP621V50GLWpE0+Evzej9NYrNH2mjSB/n5BE0nL6XL5PwAzF7AejiIOmLLbQSuI29lnGhwU4s" +
            "/M4easohvU8CFL13s9OKYvyMtTlyFTH9+qDlehl+64GI5jJEV1blfYqXxDpwmsr5g1ClsljUF+9ZC9uTHwHLThRncVqLcsK93KAG" +
            "pIQT+kqoIkdD/SgkLNEKowoJL3EpWyav5pkc3/R6/T1G0WFJcLwh/d7jJM6M0zJJjiSBvhz8dhiLSpNjLqEkXWKnlRGhNH1qdAg+" +
            "IJEnGInLRau+tBt+X/XUbEFAgizFMeu4+1z0azVSM258kc0lRap7ObNUnsWsuOIXSG7nMKiA/bCX8FBA/kiOCtMQfQatxR8Qy68Z" +
            "rn7N5XXKIHG2+pqjlzOquBbwZR+dQMiYXhLODJUXaA9lYC+oGSC0x3MNx7xWwxN/LzOKt/KEKkU6o0oxe2VknSQJko/iCyl4OInA" +
            "wxDTIXE/8Ma4Ieqyxkw9134fZMUzi3vqxR5dX6HpRsDMpY99xCtWBC3DdYePLfeZZsvIf0C5Tt+HzKOLrrmKcthTjhrFLuOhMW4J" +
            "OOvLy75kcdH3XGdydcQPkCGemX1bUa0glu1q87bzcBzRlD52toQvEZIe9SRlTd8FHhvPTxosDJVVNKZsl6CcikZZsoTWchT9vUlv" +
            "aSa+DW94eiV7QC4Kv5Kmgef0R3RrliC8yRj8pRR+jJHUmBDa6F3gU6YbrliVgCvxNFC3DMqOCWuLD3VwIBSxVqYZiuh7qQ+tXZ7l" +
            "l0VQje5Nhb78pRm0Pt7hULPfv66elHIjBCwen+74dSfH20wcgxYFeanxdMI6QfRt67lYOlC/UVjfbp0Gf9nxgqQX9ioWhjsDHoIh" +
            "Z+qb8+ugmLHZipMmWaE6Y+Ym/wfaUvzY"
            ;
    /** 行数（自检对账） */
    static final int COUNT = 447;

    private BlendTable() {}
}
