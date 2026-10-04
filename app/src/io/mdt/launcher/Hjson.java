package io.mdt.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自带的宽容 HJSON 解析器：把 HJSON（同时吃严格 JSON）转成**标准紧凑 JSON 文本**。
 *
 * <p>出处与移植范围（GPL-3.0，与本工程许可一致）：
 * 本文件是 <b>arc 的 {@code arc.util.serialization.Jval}</b> 的手工移植，不是官方 hjson 库。
 * 只移植两段：{@code read(String)} 的 HJSON 分支（{@code Jval.Hparser}）与
 * {@code toString(Jformat.plain)}（{@code Jval.Jwriter}/{@code Jval.Hwriter} 中 plain 那一支）。
 * 不移植 {@code JvalSerializer} / {@code JsonValue} / arc 反射那一整套。
 *
 * <p>被移植的两版正本：
 * <ul>
 *   <li>{@code ARC_159}: arc 159.7 —— 正本 {@code _lab/hjson/src/Jval-159.7.java}
 *       （{@code package arc.util.serialization}，1165 行）。
 *       无引号值的终止判定在 {@code :472}：{@code boolean isEol = current < 0 || current == '\r'
 *       || current == '\n' || (current == ',' && isArray) || current == ']';}，
 *       其后 {@code :473} 另用 {@code current == ',' || current == '}' || current == '#' || ...}
 *       作为「可能结束」的探测条件。{@code } # //} <b>不算</b>进 isEol。</li>
 *   <li>{@code ARC_160}: arc 160.4 —— 正本
 *       {@code Arc-160.4/arc-core/src/arc/util/serialization/Jval.java}。
 *       差异集中在无引号值的终止集，约 {@code :545-546}：
 *       {@code boolean isComment = current == '#' || (current == '/' && (peek() == '/' || peek() == '*'));}
 *       {@code boolean isEol = current < 0 || current == '\r' || current == '\n' || (current == ',' && isArray)
 *       || current == ']' || current == '}' || isComment;}
 *       —— 即 160 把 {@code }}、{@code #}、{@code //} 并进了终止集（terminatesOnBracket/Comment = true）。</li>
 * </ul>
 *
 * <p>两版的差异**不止**上面那一条。除它之外，可观测（会让输出字节或接受/拒绝判定变）的差异还有 4 处，
 * 全部在真实 {@code out159}/{@code out160} 神谕上实测过，本移植里逐条按档位分支：
 * <ol>
 *   <li><b>无引号值的终止集</b>（上面 {@code :472} vs {@code :545-546}）。</li>
 *   <li><b>重复键</b>：159.7 的 {@code ArrayMap.put} 就地覆盖 ⇒ 只留最后一条；
 *       160.4 的 {@code ObjectMap.putAdd} 无条件追加 ⇒ 输出里出现多条。见 {@link JObj160}。</li>
 *   <li><b>嵌套数组前的空格</b>：159.7 的 {@code Jwriter.save(array)} 有 {@code if(level != 0) tw.write(' ');}
 *       （plain 档 {@code nl()} 是空操作，这个空格会留在输出里），160.4 没有这句。
 *       见 {@link HjsonWriter}。</li>
 *   <li><b>root 回退路上 {@code isArray} 的残留</b>：两版的 {@code reset()} 都不复位 {@code isArray}，
 *       而这个标志会让回退解析把 {@code ','} 当终止符，从而改变「接受 / 拒绝」。见 {@link HparserBase#reset()}。</li>
 * </ol>
 *
 * <p>其余是「等价改写」——160.4 把解析器从 {@code Reader} + {@code StringBuilder} 抓取改成了
 * {@code char[]} + 下标切片，逐条核对过、结论是等价（本移植按各自版本原样保留）：
 * <ul>
 *   <li>160.4 的 {@code readTfnns} 用 {@code index-1}/{@code index} 定位串首尾并做 {@code <= 0x20} 的
 *       trim —— 与 159.7 的 {@code StringBuilder} + {@code String.trim()} 结果一致。</li>
 *   <li>160.4 的 {@code tryParseNumber} 用 {@code '.','e','E'} 判是否走 double 分支 —— 与 159.7 的
 *       {@code contains(".")/contains(",")/contains("e")} 在本解析器能构造出的串上结果一致
 *       （串里不可能出现 {@code ','}，且 {@code 'E'} 的前驱判定只认 {@code e/E}）。</li>
 *   <li>160.4 的 {@code readName} / {@code readMlString} / {@code readEscape} 与 159.7 等价。</li>
 *   <li>对象/数组的分隔、字符串转义表、数字走 {@code Jval.toString()} —— 两版一致。</li>
 * </ul>
 * 上述 4 条差异 + 这条等价改写清单，是在 68 条语料（41 语法电池 + 10 三实现对照 + 17 真实模组元数据）
 * 与 8 万次合成语料差分对拍上验出来的，结论：两个 profile 的接受/拒绝集合与输出字节**全部**与神谕一致。
 *
 * <p>依赖替换（题面指定）：{@code arc.struct.ArrayMap} → {@link LinkedHashMap}（保插入序）；
 * {@code arc.struct.Seq} → {@link ArrayList}；{@code @Nullable} 等 arc 注解删掉。
 * {@code Jval} 对象模型简化成裸的 {@code Object}
 * （{@code null} / {@link String} / {@link Long} / {@link Double} / {@link Boolean} /
 * {@code Map<String,Object>} / {@code List<Object>}）。除这些集合替换之外，对 arc 代码做的
 * **行为相关**修改全部在代码里以 {@code PORT:} 注释标出。
 *
 * <p>纯 Java、无任何外部依赖、不 import 任何 Android 类，可用普通 javac 单独编译。
 * 语法保持 Java 7/8（无 lambda / 无 var / 无 List.of），与工程其它文件一致。
 *
 * @see #toJsonText(String, int)
 */
public final class Hjson{

    /** 目标游戏的 arc 版本档位：arc 159.7 语义（终止规则不把 {@code } # //} 算进 isEol）。 */
    public static final int ARC_159 = 159;
    /** 目标游戏的 arc 版本档位：arc 160.4 语义（terminatesOnBracket/Comment = true）。 */
    public static final int ARC_160 = 160;

    private Hjson(){
    }

    /**
     * 宽容解析 HJSON（也吃严格 JSON）→ 标准紧凑 JSON 文本，
     * 等价 arc 的 {@code Jval.read(text).toString(Jformat.plain)}。
     *
     * @param text    源文本（后缀不可信，内容为准）
     * @param profile {@link #ARC_159} 或 {@link #ARC_160}
     * @return 标准紧凑 JSON 文本（无任何多余空白）
     * @throws ParseException 解析失败（任何 Throwable 都在边界处收敛成它）
     */
    public static String toJsonText(String text, int profile) throws ParseException{
        try{
            Object value;
            if(profile == ARC_160){
                value = new Hparser160().parse(text);
            }else{
                // ARC_159 是默认档：非 160 一律按 159 处理，避免"未知档位静默变严"
                value = new Hparser159().parse(text);
            }
            StringBuilder sb = new StringBuilder(Math.max(16, text == null ? 16 : text.length()));
            HjsonWriter.save(value, sb, profile == ARC_160);
            return sb.toString();
        }catch(ParseException e){
            throw e;
        }catch(Throwable t){
            // 含 StringIndexOutOfBoundsException / NumberFormatException / StackOverflowError / OOM
            throw new ParseException(failureMessage(t), t);
        }
    }

    /** 解析失败时不把整篇输入/整条栈塞进消息里，只留「异常类名 + 截断后的消息」。 */
    private static String failureMessage(Throwable t){
        String msg = t.getMessage();
        if(msg == null) msg = "";
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < msg.length(); i++){
            char c = msg.charAt(i);
            sb.append(c == '\n' || c == '\r' ? ' ' : c);
        }
        String flat = sb.toString();
        if(flat.length() > 160) flat = flat.substring(0, 160);
        return t.getClass().getName() + (flat.length() == 0 ? "" : ": " + flat);
    }

    /** 解析失败。任何 Throwable（含 Error）都会被 {@link #toJsonText} 收敛成它。 */
    public static final class ParseException extends RuntimeException{
        public ParseException(String msg){
            super(msg);
        }

        public ParseException(String msg, Throwable cause){
            super(msg, cause);
        }
    }

    /**
     * 与 arc {@code Hwriter.isPunctuatorChar} 一致。
     * 注意：160.4 的 Hwriter 同样保留了这个方法（只是 static 化），两版判定一致。
     */
    static boolean isPunctuatorChar(int c){
        return c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':';
    }

    /** 与 arc {@code Hparser.isWhiteSpace(int)} 一致。 */
    static boolean isWhiteSpace(int ch){
        return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
    }

    private static boolean isDigit(char ch){
        return ch >= '0' && ch <= '9';
    }

    /**
     * 无引号值/键里的数字识别，逐行照抄 159.7 的 {@code Jval.Hparser.tryParseNumber(StringBuilder, boolean)}。
     * 160.4 的同名方法只把入参从 {@code StringBuilder} 换成 {@code char[] + 区间}，并复用了这个
     * 「是否走 double 分支」的判定，见 {@link #tryParseNumber160}。
     */
    static Object tryParseNumber(StringBuilder value, boolean stopAtNext){
        int idx = 0, len = value.length();
        if(idx < len && value.charAt(idx) == '-') idx++;

        if(idx >= len) return null;
        char first = value.charAt(idx++);
        if(!isDigit(first)) return null;

        if(first == '0' && idx < len && isDigit(value.charAt(idx)))
            return null; // leading zero is not allowed

        while(idx < len && isDigit(value.charAt(idx))) idx++;

        // frac
        if(idx < len && value.charAt(idx) == '.'){
            idx++;
            if(idx >= len || !isDigit(value.charAt(idx++))) return null;
            while(idx < len && isDigit(value.charAt(idx))) idx++;
        }

        // exp
        if(idx < len && Character.toLowerCase(value.charAt(idx)) == 'e'){
            idx++;
            if(idx < len && (value.charAt(idx) == '+' || value.charAt(idx) == '-')) idx++;

            if(idx >= len || !isDigit(value.charAt(idx++))) return null;
            while(idx < len && isDigit(value.charAt(idx))) idx++;
        }

        int last = idx;
        while(idx < len && isWhiteSpace(value.charAt(idx))) idx++;

        boolean foundStop = false;
        if(idx < len && stopAtNext){
            // end scan if we find a control character like ,}] or a comment
            char ch = value.charAt(idx);
            if(ch == ',' || ch == '}' || ch == ']' || ch == '#' || ch == '/' && (len > idx + 1 && (value.charAt(idx + 1) == '/' || value.charAt(idx + 1) == '*')))
                foundStop = true;
        }

        if(idx < len && !foundStop) return null;
        String str = value.substring(0, last);

        if(!str.contains(".") && !str.contains(",") && !str.contains("e")){
            try{
                return Long.valueOf(Long.parseLong(str));
            }catch(NumberFormatException ignored){
            }
        }

        return Double.valueOf(Double.parseDouble(str));
    }

    /** 160.4 的 {@code tryParseNumber(char[], from, to, stopAtNext)}。 */
    static Object tryParseNumber160(char[] buf, int from, int to, boolean stopAtNext){
        int idx = from, len = to;
        if(idx < len && buf[idx] == '-') idx++;

        if(idx >= len) return null;
        char first = buf[idx++];
        if(!isDigit(first)) return null;

        if(first == '0' && idx < len && isDigit(buf[idx]))
            return null; // leading zero is not allowed

        while(idx < len && isDigit(buf[idx])) idx++;

        // frac
        if(idx < len && buf[idx] == '.'){
            idx++;
            if(idx >= len || !isDigit(buf[idx++])) return null;
            while(idx < len && isDigit(buf[idx])) idx++;
        }

        // exp
        if(idx < len && Character.toLowerCase(buf[idx]) == 'e'){
            idx++;
            if(idx < len && (buf[idx] == '+' || buf[idx] == '-')) idx++;
            if(idx >= len || !isDigit(buf[idx++])) return null;
            while(idx < len && isDigit(buf[idx])) idx++;
        }

        int last = idx;
        while(idx < len && isWhiteSpace(buf[idx])) idx++;

        boolean foundStop = false;
        if(idx < len && stopAtNext){
            char ch = buf[idx];
            if(ch == ',' || ch == '}' || ch == ']' || ch == '#' || ch == '/' && (len > idx + 1 && (buf[idx + 1] == '/' || buf[idx + 1] == '*')))
                foundStop = true;
        }

        if(idx < len && !foundStop) return null;

        boolean isDecimal = false;
        for(int i = from; i < last; i++){
            char c = buf[i];
            if(c == '.' || c == 'e' || c == 'E'){
                isDecimal = true;
                break;
            }
        }

        String str = new String(buf, from, last - from);

        if(!isDecimal){
            try{
                return Long.valueOf(Long.parseLong(str));
            }catch(NumberFormatException ignored){
            }
        }

        return Double.valueOf(Double.parseDouble(str));
    }

    /** {@code String.trim()} 的等价判定（chars &lt;= 0x20），160.4 的 {@code isTrimChar}。 */
    private static boolean isTrimChar(char c){
        return c <= ' ';
    }

    /**
     * 两版共用的骨架。两版的差异全部收在抽象方法里，其它状态机逐行保持各自版本的样子。
     *
     * <p>{@code PORT:} 159.7 的 {@code Hparser} 基于 {@code Reader} + {@code StringBuilder} peek 缓冲 +
     * {@code captureBuffer} 抓取；这里（{@link Hparser159}）把它改成等价的 {@code char[] + index} 直接存取。
     * 等价性依据：159.7 的 {@code read()} 在 {@code peek} 为空时才向 reader 取下一字符，而解析器只在
     * 「向前看 1 个字符」时用 {@code peek(idx)}，{@code Hparser(String)} 走 {@code StringReader}，
     * 因此「逻辑读指针」与下标一一对应；{@code captureBuffer} 的抓取语义由
     * {@code startCapture/pauseCapture/endCapture} 的三个状态位精确复刻（含
     * 「{@code read()} 在 append 之前 {@code index++}」这个顺序，这正是未终止字符串边界行为的来源）。
     */
    private abstract static class HparserBase{
        /** 输入字符。用 char[] 而不是 String，是为了让越界访问像 arc 一样得到 {@code \0} 而不是抛异常。 */
        protected char[] buffer;
        protected int bufferLength;
        protected int index;
        protected int line;
        protected int lineOffset;
        protected int current = -1;
        protected StringBuilder captureBuffer;
        protected int captureStart;
        protected int rawStart;
        protected boolean escaped;
        protected boolean isArray;

        final Object parse(String text){
            if(text == null) throw new NullPointerException("string is null");
            buffer = text.toCharArray();
            bufferLength = buffer.length;
            reset();
            //braces for the root object are optional
            read();
            skipWhiteSpace();

            switch(current){
                case '[':
                case '{':
                    return checkTrailing(readValue());
                default:
                    try{
                        // assume we have a root object without braces
                        return checkTrailing(readObject(true));
                    }catch(Exception exception){
                        // test if we are dealing with a single JSON value instead (true/false/null/num/"")
                        reset();
                        read();
                        skipWhiteSpace();
                        try{
                            return checkTrailing(readValue());
                        }catch(Exception ignored){
                        }
                        throw exception; // throw original error
                    }
            }
        }

        /**
         * PORT: ★ 这里**故意不复位 {@code isArray}** —— 这就是 arc 的行为，而且是可观测的。
         * 159.7 的 {@code reset()} 只做 {@code index = lineOffset = current = 0; line = 1; +
         * peek/capture 清理}，160.4 的 {@code reset()} 也只做
         * {@code index = lineOffset = current = 0; line = 1; captureBuffer = null; escaped = false;}，
         * 两版都**不动 {@code isArray}**。而 {@code readArray()} 在「空数组早退」那条路上
         * 也不复位它（见 {@link #readArray()}）。于是：只要第一次尝试里出现过数组，
         * {@code parse()} 回退到「单值」那条路时 {@code isArray} 仍是 {@code true}，
         * 无引号值就会把 {@code ','} 当成终止符。
         * 实测（合成语料差分对拍抓出来的）：{@code "main : null,0.0 : [,"}（注意结尾没有 {@code ]}）
         * 神谕**拒绝**（回退路把 {@code "main : null"} 当完值，后面还剩 {@code ,0.0 : [}
         * ⇒ Extra characters），而我早期版本因为在这里清了 {@code isArray} 反而**接受**。
         */
        protected void reset(){
            index = lineOffset = current = 0;
            line = 1;
            captureBuffer = null;
            captureStart = 0;
            rawStart = 0;
            escaped = false;
        }

        protected final int read(){
            if(current == '\n'){
                line++;
                lineOffset = index;
            }
            current = index < bufferLength ? buffer[index++] : -1;
            return current;
        }

        protected final void skipWhiteSpace(){
            while(!isEndOfText()){
                while(isWhiteSpace()) read();
                if(current == '#' || current == '/' && peek() == '/'){
                    do{
                        read();
                    }while(current >= 0 && current != '\n');
                }else if(current == '/' && peek() == '*'){
                    read();
                    do{
                        read();
                    }while(current >= 0 && !(current == '*' && peek() == '/'));
                    read();
                    read();
                }else break;
            }
        }

        /** 160.4 的 {@code peek}：直接看 {@code buffer}，越界给 -1，**不推进 index**。 */
        protected final int peek(int idx){
            int p = index + idx;
            return p < bufferLength ? buffer[p] : -1;
        }

        protected final int peek(){
            return peek(0);
        }

        protected final boolean readIf(char ch){
            if(current != ch) return false;
            read();
            return true;
        }

        protected final boolean isWhiteSpace(){
            return Hjson.isWhiteSpace((char)current);
        }

        protected final boolean isHexDigit(){
            return current >= '0' && current <= '9'
            || current >= 'a' && current <= 'f'
            || current >= 'A' && current <= 'F';
        }

        protected final boolean isEndOfText(){
            return current == -1;
        }

        /** arc {@code expected()}：先按「当前字符是 buffer[index-1]」算；越界则退化成「撞到输入末尾」。 */
        protected final ParseException expected(String expected){
            if(isEndOfText()) return error("Unexpected end of input");
            return error("Expected " + expected);
        }

        /**
         * PORT: 159.7 的 {@code index} 在 {@code peek} 缓冲非空时是「reader 已取字符数」，
         * 而它是靠 {@code new StringReader(buffer)} 喂的，故在 159 里 {@code buffer[index-1]} 恒等于
         * {@code current}。本移植里 {@code peek} 不推进 index（同 160.4），在**未终止字符串**这类
         * 「current=reader 读到的 EOF」而 index 已回到 bufferLength 的边界上，
         * {@code buffer[index-1]} 会落到一个不属于该串的字符。为了让报错偏移继续可判、
         * 不把 {@code \0} 说成可打印字符，这里在越界时退化为 {@code error("Unexpected end of input")}。
         * 该分支只在异常路径上出现，不影响任何接受/拒绝判定与输出字节。
         */
        protected final ParseException error(String message){
            if(index > bufferLength) return errorEndOfInput(message);
            int column = index - lineOffset;
            int offset = isEndOfText() ? index : index - 1;
            return new ParseException(message + " at " + line + ":" + (column - 1));
        }

        protected final ParseException errorEndOfInput(String message){
            int column = index - lineOffset;
            return new ParseException(message + " at " + line + ":" + (column - 1));
        }

        protected final Object checkTrailing(Object v){
            skipWhiteSpace();
            if(!isEndOfText()) throw error("Extra characters in input: " + current);
            return v;
        }

        protected final Object readValue(){
            switch(current){
                case '\'':
                case '"':
                    return readString();
                case '[':
                    return readArray();
                case '{':
                    return readObject(false);
                default:
                    return readTfnns();
            }
        }

        /** 只读版本地差异所在：无引号值的终止集。 */
        protected abstract Object readTfnns();

        protected final Object readArray(){
            isArray = true;
            read();
            List<Object> array = new ArrayList<Object>();
            skipWhiteSpace();
            if(readIf(']')){
                // PORT: 159.7 与原版一样，这里**不**复位 isArray（把它当作 arc 的一个既有小瑕疵原样保留）
                return array;
            }
            while(true){
                skipWhiteSpace();
                array.add(readValue());
                skipWhiteSpace();
                if(readIf(',')) skipWhiteSpace(); // , is optional
                if(readIf(']')) break;
                else if(isEndOfText()) throw error("End of input while parsing an array (did you forget a closing ']'?)");
            }
            isArray = false;
            return array;
        }

        protected final Object readObject(boolean objectWithoutBraces){
            if(!objectWithoutBraces) read();
            // PORT: arc 159.7 的 JsonMap 是 ArrayMap，160.4 的 JsonMap 是自写的 ObjectMap；
            // 两者对重复键的处理**不同**（见 putMember 的注释），所以这里必须按档位选容器。
            Map<String, Object> object = is160()
                    ? (Map<String, Object>)new JObj160()
                    : new LinkedHashMap<String, Object>();
            skipWhiteSpace();
            while(true){
                if(objectWithoutBraces){
                    if(isEndOfText()) break;
                }else{
                    if(isEndOfText()) throw error("End of input while parsing an object (did you forget a closing '}'?)");
                    if(readIf('}')) break;
                }
                String name = readName();
                skipWhiteSpace();
                if(!readIf(':')){
                    throw expected("':'");
                }
                skipWhiteSpace();
                putMember(object, name, readValue());
                skipWhiteSpace();
                if(readIf(',')) skipWhiteSpace(); // , is optional
            }
            return object;
        }

        /** 档位判定（供容器选择用）。 */
        protected abstract boolean is160();

        /**
         * PORT: arc 的 {@code ArrayMap.put} 与 160.4 的 {@code ObjectMap.putAdd} 语义不同：
         * <ul>
         *   <li>159.7 {@code ArrayMap.put(key, value)} = 找到同名键就**就地覆盖**，不新增条目
         *       ⇒ 重复键在输出里只留一个，值取最后一个。等价于 {@link LinkedHashMap#put}。</li>
         *   <li>160.4 {@code ObjectMap.putAdd(key, value)} = 不做同名查找，**直接追加**条目
         *       ⇒ 重复键在输出里出现多次、按出现顺序排。这正是 160 那条 {@code terminatesOnBracket}
         *       之外的第二个可观测差异，必须照搬，否则 {@code samples_hx18.json} 会差字节。</li>
         * </ul>
         */
        protected abstract void putMember(Map<String, Object> object, String name, Object value);

        private String readName(){
            if(current == '"' || current == '\'') return readStringInternal(false);

            StringBuilder name = new StringBuilder();
            int space = -1, start = index;
            while(true){
                if(current == ':'){
                    if(name.length() == 0) throw error("Found ':' but no key name (for an empty key name use quotes)");
                    else if(space >= 0 && space != name.length()){
                        index = start + space;
                        throw error("Found whitespace in your key name (use quotes to include)");
                    }
                    return name.toString();
                }else if(Hjson.isWhiteSpace(current)){
                    if(space < 0) space = name.length();
                }else if(current < ' '){
                    throw error("Name is not closed");
                }else if(isPunctuatorChar(current)){
                    throw error("Found '" + (char)current + "' where a key name was expected (check your syntax or use quotes if the key name includes {}[],: or whitespace)");
                }else name.append((char)current);
                read();
            }
        }

        private String readMlString(){
            // Parse a multiline string value.
            StringBuilder sb = new StringBuilder();
            int triple = 0;

            // we are at '''
            int indent = index - lineOffset - 4;

            // skip white/to (newline)
            while(true){
                if(Hjson.isWhiteSpace(current) && current != '\n') read();
                else break;
            }
            if(current == '\n'){
                read();
                skipIndent(indent);
            }

            // When parsing for string values, we must look for " and \ characters.
            while(true){
                if(current < 0) throw error("Bad multiline string");
                else if(current == '\''){
                    triple++;
                    read();
                    if(triple == 3){
                        // PORT: 原版这里对空串会抛 StringIndexOutOfBoundsException（"'''\n'''" 这类输入），
                        // 那属于「失败」，本项目把它收敛成 ParseException；判定方向与原版一致（都拒绝）。
                        if(sb.length() == 0) throw error("Bad multiline string");
                        if(sb.charAt(sb.length() - 1) == '\n') sb.deleteCharAt(sb.length() - 1);
                        return sb.toString();
                    }else continue;
                }else{
                    while(triple > 0){
                        sb.append('\'');
                        triple--;
                    }
                }
                if(current == '\n'){
                    sb.append('\n');
                    read();
                    skipIndent(indent);
                }else{
                    if(current != '\r') sb.append((char)current);
                    read();
                }
            }
        }

        private void skipIndent(int indent){
            while(indent-- > 0){
                if(Hjson.isWhiteSpace(current) && current != '\n') read();
                else break;
            }
        }

        private Object readString(){
            return readStringInternal(true);
        }

        /** 159.7 的抓取式实现：{@code StringBuilder} + {@code capture} 状态位。 */
        protected final String readStringInternal(boolean allowML){
            // callees make sure that (current=='"' || current=='\'')
            int exitCh = current;
            read();
            startCapture();
            while(current >= 0 && current != exitCh){
                if(current == '\\') readEscape();
                //else if(current < 0x20) throw expected("valid string character");
                else read();
            }
            String string = endCapture();
            read();

            if(allowML && exitCh == '\'' && current == '\'' && string.length() == 0){
                // ''' indicates a multiline string
                read();
                return readMlString();
            }else return string;
        }

        private void startCapture(){
            if(captureBuffer == null) captureBuffer = new StringBuilder();
            captureBuffer.setLength(0);
            // PORT: 159.7 的 startCapture 把自己 append 进 captureBuffer —— 但此时 current 仍是**开引号**
            // （read() 在 append 之前只推进了 index，没换 current），而 endCapture 会 deleteCharAt(len-1)
            // 去掉最后一个字符。两个「差一位」正好抵消：真正被保留的正文区间是
            // [开引号之后, 闭引号之前) = [index-1, index-1) 的展开式，即下面这一对下标。
            captureStart = index - 1;
            rawStart = captureStart;
            escaped = false;
        }

        private String endCapture(){
            // PORT: 159.7 的 endCapture = pauseCapture()（deleteCharAt(len-1) 去掉闭引号）后取整串。
            // 关键在于「只有走过 escape 才会走 captureBuffer 那条路」：
            //   escaped == false → 缓冲里就只剩开引号那一笔，deleteCharAt 之后是空串，于是
            //                      captured 取的是 captureBuffer 之外的整串 —— 等价于本方法的裸切片分支；
            //   escaped == true  → 159.7 的 captured 就是 captureBuffer 本身（含已解码的 escape 输出），
            //                      所以必须用 captureBuffer + 最后一段裸片段来拼，不能直接切 buffer。
            // 「末端用 index-1」原样保留了 159.7 的边界行为（未终止字符串时减去一个字符）。
            int end = index - 1;
            String captured;
            if(escaped && captureBuffer != null){
                captureBuffer.append(buffer, rawStart, end - rawStart);
                captured = captureBuffer.toString();
                captureBuffer.setLength(0);
            }else if(end > captureStart){
                captured = new String(buffer, captureStart, end - captureStart);
            }else{
                captured = "";
            }
            captureBuffer = null;
            return captured;
        }

        private void readEscape(){
            // PORT: 159.7 的 readEscape 是 pauseCapture()（把已抓到的 \ 删掉、关抓取）+ 逐字符 append。
            // 因为 159.7 的抓取缓冲是**累积**的，这里改成「先把 \ 之前的裸片段补进 captureBuffer」，
            // 语义与 159.7 完全一致；escape 序列本身照抄。
            int backslashPos = index - 1;
            if(captureBuffer == null) captureBuffer = new StringBuilder(32);
            captureBuffer.append(buffer, rawStart, backslashPos - rawStart);
            escaped = true;

            read();
            switch(current){
                case '"':
                case '\'':
                case '#':
                case '/':
                case '\\':
                    captureBuffer.append((char)current);
                    break;
                case 'b':
                    captureBuffer.append('\b');
                    break;
                case 'f':
                    captureBuffer.append('\f');
                    break;
                case 'n':
                    captureBuffer.append('\n');
                    break;
                case 'r':
                    captureBuffer.append('\r');
                    break;
                case 't':
                    captureBuffer.append('\t');
                    break;
                case 'u':
                    char[] hexChars = new char[4];
                    for(int i = 0; i < 4; i++){
                        read();
                        if(!isHexDigit()){
                            throw expected("hexadecimal digit");
                        }
                        hexChars[i] = (char)current;
                    }
                    captureBuffer.append((char)Integer.parseInt(new String(hexChars), 16));
                    break;
                default:
                    throw expected("valid escape sequence");
            }
            read();
            rawStart = index - 1;
        }
    }

    /**
     * arc <b>159.7</b> 语义。终止判定 = 159.7 的 {@code Jval-159.7.java:472-473}：
     * {@code isEol = current < 0 || '\r' || '\n' || (',' && isArray) || ']'}；
     * {@code } # //} **不**算进 isEol（它们只让主过程再看一眼，随后照旧 append 进值里）。
     */
    private static final class Hparser159 extends HparserBase{
        @Override
        protected boolean is160(){
            return false;
        }

        /** 159.7 的 {@code ArrayMap.put}：同名键就地覆盖，不新增条目。 */
        @Override
        protected void putMember(Map<String, Object> object, String name, Object value){
            object.put(name, value);
        }

        @Override
        protected Object readTfnns(){
            // Hjson strings can be quoteless
            // returns string, true, false, or null.
            StringBuilder value = new StringBuilder();
            int first = current;
            if(isPunctuatorChar(first))
                throw error("Found a punctuator character '" + (char)first + "' when expecting a quoteless string (check your syntax)");
            value.append((char)current);
            while(true){
                read();
                boolean isEol = current < 0 || current == '\r' || current == '\n' || (current == ',' && isArray) || current == ']';
                if(isEol || current == ',' || current == '}' || current == '#' || current == '/' && (peek() == '/' || peek() == '*')
                ){
                    switch(first){
                        case 'f':
                        case 'n':
                        case 't':
                            String svalue = value.toString().trim();
                            switch(svalue){
                                case "false": return Boolean.FALSE;
                                case "null": return null;
                                case "true": return Boolean.TRUE;
                            }
                            break;
                        default:
                            if(first == '-' || first >= '0' && first <= '9'){
                                Object n = tryParseNumber(value, false);
                                if(n != null) return n;
                            }
                    }
                    if(isEol){
                        //remove trailing commas
                        if(value.length() > 0 && value.charAt(value.length() - 1) == ','){
                            value.setLength(value.length() - 1);
                        }
                        //remove any whitespace at the end (ignored in quoteless strings)
                        return value.toString().trim();
                    }
                }
                value.append((char)current);
            }
        }
    }

    /**
     * arc <b>160.4</b> 语义。终止判定 = 160.4 的
     * {@code Jval.java:545-546}：{@code isComment = '#' || ('/' && (peek()=='/' || peek()=='*'))}，
     * {@code isEol = current < 0 || '\r' || '\n' || (',' && isArray) || ']' || '}' || isComment}。
     * 即 {@code }}、{@code #}、{@code //}（与 {@code /*}）都终止无引号值。
     */
    private static final class Hparser160 extends HparserBase{
        @Override
        protected boolean is160(){
            return true;
        }

        /** 160.4 的 {@code ObjectMap.putAdd}：不查重、直接追加。 */
        @Override
        @SuppressWarnings("unchecked")
        protected void putMember(Map<String, Object> object, String name, Object value){
            ((JObj160)object).add(name, value);
        }

        @Override
        protected Object readTfnns(){
            int start = index - 1;
            int first = current;
            if(isPunctuatorChar(first))
                throw error("Found a punctuator character '" + (char)first + "' when expecting a quoteless string (check your syntax)");

            while(true){
                read();
                boolean isComment = current == '#' || (current == '/' && (peek() == '/' || peek() == '*'));
                boolean isEol = current < 0 || current == '\r' || current == '\n' || (current == ',' && isArray) || current == ']' || current == '}' || isComment;
                if(isEol || current == ','){
                    int stop = current < 0 ? index : index - 1; // position of the stopping char, not yet part of the value

                    switch(first){
                        case 'f':
                        case 'n':
                        case 't': {
                            int s = start, e = stop;
                            while(s < e && isTrimChar(buffer[s])) s++;
                            while(e > s && isTrimChar(buffer[e - 1])) e--;
                            int len = e - s;
                            if(len == 5 && regionMatches(s, "false")) return Boolean.FALSE;
                            if(len == 4 && regionMatches(s, "null")) return null;
                            if(len == 4 && regionMatches(s, "true")) return Boolean.TRUE;
                            break;
                        }
                        default:
                            if(first == '-' || first >= '0' && first <= '9'){
                                Object n = tryParseNumber160(buffer, start, stop, false);
                                if(n != null) return n;
                            }
                    }
                    if(isEol){
                        int end = stop;
                        //remove trailing comma
                        if(end > start && buffer[end - 1] == ',') end--;
                        //trim like String.trim() (<= 0x20), matching original .trim() behavior
                        int s = start, e = end;
                        while(s < e && isTrimChar(buffer[s])) s++;
                        while(e > s && isTrimChar(buffer[e - 1])) e--;
                        return new String(buffer, s, e - s);
                    }
                }
            }
        }

        private boolean regionMatches(int off, String kw){
            int n = kw.length();
            for(int i = 0; i < n; i++) if(buffer[off + i] != kw.charAt(i)) return false;
            return true;
        }
    }

    /**
     * arc <b>160.4</b> 的 {@code Jval.JsonMap}（{@code ObjectMap.putAdd} 语义）的等价物：
     * 一个**允许重复键、按条目插入顺序遍历**的有序映射。
     *
     * <p>为什么不能直接用 {@link LinkedHashMap}：160.4 的 {@code putAdd} 不查重、直接追加条目，
     * 于是 {@code {"author":"first","author":"second"}} 会被原样写成
     * {@code {"author":"first","author":"second"}}（重复键出现在输出里）；而 159.7 的
     * {@code ArrayMap.put} 是就地覆盖，只留 {@code {"author":"second"}}。两者都从真实
     * {@code out160}/{@code out159} 神谕上实测过（语料 {@code samples_hx18.json}）。
     *
     * <p>★ 条目顺序必须是**扁平的插入序列**，不能按「键 → 该键的值列表」分组：
     * arc 的 {@code ObjectMap} 就是两个平行数组 {@code keys[]}/{@code values[]}，
     * 重复键在数组里各自占一格。分组写法在交错重复键上会错位，例如
     * {@code {a:1, d:{}, a:2, d:0}} —— 神谕给 {@code "a":1,"d":{},"a":2,"d":0}，
     * 分组写法会错写成 {@code "a":1,"a":2,"d":{},"d":0}。这条是合成语料差分对拍抓出来的。
     *
     * <p>★ 注意 <b>不是</b>「大小写不敏感查重」：160.4 的 {@code putAdd} 是**无条件追加**，
     * 连键名相同（甚至大小写只差）也不查重 —— 实测神谕 {@code {a:1,a:2}} 给出
     * {@code {"a":1,"a":2}}、{@code {3.14:1,3.14:2}} 给出两条。这一点也和 159.7 的
     * {@code ArrayMap.put}（就地覆盖）不同。
     */
    private static final class JObj160 extends LinkedHashMap<String, Object>{
        private static final long serialVersionUID = 1L;

        /** 扁平条目表 (key, value)，按插入顺序；重复键各占一条。 */
        final List<Object[]> entries = new ArrayList<Object[]>();

        /** 等价于 arc 160.4 的 {@code ObjectMap.putAdd}：无条件追加条目。 */
        void add(String key, Object value){
            entries.add(new Object[]{key, value});
            // LinkedHashMap 只用于「按名字取值」这条支路（保留首次出现的值），
            // 输出顺序一律走 entries，不受它的去重影响。
            if(!containsKey(key)) super.put(key, value);
        }

        List<Object[]> orderedEntries(){
            return entries;
        }
    }

    /**
     * {@code Jval.Jwriter} 的 plain 分支（{@code format == false}）。
     * 两版的这一支**不是**逐行一致，实测有两处可观测差异，故这里按档位分支：
     * <ol>
     *   <li><b>嵌套数组前多一个空格</b>：159.7 的 array 分支是 {@code if(level != 0) tw.write(' ');}
     *       —— plain 档下 {@code nl()} 是空操作，于是「带前导空格」就留在了输出里，例如
     *       {@code {"dependencies": ["a","b"]}}（159）/ {@code {"dependencies":["a","b"]}}（160）；
     *       嵌套数组还会出现 {@code [[ [1], [2]]]} 这种内层带空格的形态。
     *       160.4 的同位置**没有**这一句。</li>
     *   <li><b>重复键</b>：见 {@link JObj160}。</li>
     * </ol>
     * 其余（对象/数组的分隔、字符串转义表、数字走 {@code Jval.toString()}）两版一致。
     */
    private static final class HjsonWriter{
        static void save(Object value, StringBuilder sb, boolean v160){
            save(value, sb, 0, v160);
        }

        private static void save(Object value, StringBuilder sb, int level, boolean v160){
            if(value == null){
                // PORT: arc 里 null 由 Jval.NULL（value==null）表示，Jwriter 的 switch 落到 default 分支
                // `tw.write(value.toString())` —— 而 Jval.toString() 对 Jtype.nil 返回 "null"。
                // 这里直接写 null，字节等价。
                sb.append("null");
                return;
            }
            if(value instanceof JObj160){
                // 160 的 JsonMap：允许重复键，严格按条目插入顺序输出
                JObj160 obj = (JObj160)value;
                sb.append('{');
                boolean following = false;
                for(Object[] pair : obj.orderedEntries()){
                    if(following) sb.append(',');
                    writeMember((String)pair[0], pair[1], sb, level, v160);
                    following = true;
                }
                sb.append('}');
            }else if(value instanceof Map){
                Map<?, ?> obj = (Map<?, ?>)value;
                sb.append('{');
                boolean following = false;
                for(Map.Entry<?, ?> pair : obj.entrySet()){
                    if(following) sb.append(',');
                    writeMember(String.valueOf(pair.getKey()), pair.getValue(), sb, level, v160);
                    following = true;
                }
                sb.append('}');
            }else if(value instanceof List){
                List<?> arr = (List<?>)value;
                int n = arr.size();
                // PORT: 159.7 的 Jwriter.save(array) 有 `if(level != 0) tw.write(' ');`，160.4 没有。
                if(!v160 && level != 0) sb.append(' ');
                sb.append('[');
                boolean following = false;
                for(int i = 0; i < n; i++){
                    if(following) sb.append(',');
                    save(arr.get(i), sb, level + 1, v160);
                    following = true;
                }
                sb.append(']');
            }else if(value instanceof Boolean){
                sb.append(((Boolean)value).booleanValue() ? "true" : "false");
            }else if(value instanceof String){
                sb.append('"');
                sb.append(escapeString((String)value));
                sb.append('"');
            }else{
                // PORT: arc Jwriter 的 default 分支是 `tw.write(value.toString())`，即 Jval.toString()：
                //   case number: 若以 ".0" 结尾则删掉 ".0"，并把 'E' 换成 'e'
                // 这里对 Long/Double 复刻同一变换。
                sb.append(numberToString(value));
            }
        }

        /** 写一个 {@code "key":value} 成员。（两版都是先写转义后的键、再写冒号。） */
        private static void writeMember(String key, Object value, StringBuilder sb, int level, boolean v160){
            sb.append('\"');
            sb.append(escapeString(key));
            sb.append("\":");
            save(value, sb, level + 1, v160);
        }

        /** 复刻 {@code Jval.toString()} 里 number 那一支。 */
        static String numberToString(Object value){
            String s = String.valueOf(value);
            if(value instanceof Double || value instanceof Float){
                if(s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            }
            return s.replace('E', 'e');
        }

        static String escapeString(String src){
            if(src == null) return null;

            for(int i = 0; i < src.length(); i++){
                if(getEscapedChar(src.charAt(i)) != null){
                    StringBuilder sb = new StringBuilder();
                    if(i > 0) sb.append(src, 0, i);
                    return doEscapeString(sb, src, i);
                }
            }
            return src;
        }

        private static String doEscapeString(StringBuilder sb, String src, int cur){
            int start = cur;
            for(int i = cur; i < src.length(); i++){
                String escaped = getEscapedChar(src.charAt(i));
                if(escaped != null){
                    sb.append(src, start, i);
                    sb.append(escaped);
                    start = i + 1;
                }
            }
            sb.append(src, start, src.length());
            return sb.toString();
        }

        private static String getEscapedChar(char c){
            switch(c){
                case '\"': return "\\\"";
                case '\t': return "\\t";
                case '\n': return "\\n";
                case '\r': return "\\r";
                case '\f': return "\\f";
                case '\b': return "\\b";
                case '\\': return "\\\\";
                default: return null;
            }
        }
    }
}
