# A method body as ECMA-335 Partition III instructions, with every token resolved to the name it
# stands for, so that what a platform's own provider does is read from the machine rather than
# supposed. Dot-source it from a dump script beside reading-environment.ps1.
#
# The opcode table is System.Reflection.Emit.OpCodes itself, read by reflection, so the operand
# widths come from the runtime that runs this and not from a table typed in here: an instruction
# whose opcode the table does not know stops the listing and says where, rather than misreading
# every byte after it.

$script:ilOneByte = @{}
$script:ilTwoByte = @{}
foreach ($f in [System.Reflection.Emit.OpCodes].GetFields([System.Reflection.BindingFlags]'Public,Static')) {
    $op = [System.Reflection.Emit.OpCode]$f.GetValue($null)
    $value = [int]($op.Value -band 0xFFFF)
    if ($op.Size -eq 1) { $script:ilOneByte[$value] = $op } else { $script:ilTwoByte[$value -band 0xFF] = $op }
}

function Get-ILOperandSize($op, [byte[]]$il, [int]$at) {
    switch ($op.OperandType.ToString()) {
        'InlineNone' { return 0 }
        'ShortInlineBrTarget' { return 1 }
        'ShortInlineI' { return 1 }
        'ShortInlineVar' { return 1 }
        'InlineVar' { return 2 }
        'InlineI8' { return 8 }
        'InlineR' { return 8 }
        'InlineSwitch' { return 4 + 4 * [System.BitConverter]::ToInt32($il, $at) }
        default { return 4 }
    }
}

# The name a metadata token stands for, in the method's own generic context.
$script:ilTokenNames = @{}
function Resolve-ILToken($method, [int]$token) {
    # One resolution per module and token outside a generic context: a search over a whole
    # framework assembly resolves the same few call targets hundreds of thousands of times.
    $generic = $false
    try { $generic = $method.DeclaringType.IsGenericType -or $method.IsGenericMethod } catch { }
    $key = "$($method.Module.ModuleVersionId):$token"
    if (-not $generic -and $script:ilTokenNames.ContainsKey($key)) { return $script:ilTokenNames[$key] }
    $name = Resolve-ILTokenUncached $method $token
    if (-not $generic) { $script:ilTokenNames[$key] = $name }
    return $name
}

function Resolve-ILTokenUncached($method, [int]$token) {
    $typeArgs = $null
    $methodArgs = $null
    try { if ($method.DeclaringType.IsGenericType) { $typeArgs = $method.DeclaringType.GetGenericArguments() } } catch { }
    try { if ($method.IsGenericMethod) { $methodArgs = $method.GetGenericArguments() } } catch { }
    try {
        $member = $method.Module.ResolveMember($token, $typeArgs, $methodArgs)
        if ($member -is [System.Type]) { return $member.FullName }
        $parameters = ''
        if ($member -is [System.Reflection.MethodBase]) {
            $parameters = '(' + ((@($member.GetParameters()) | ForEach-Object { $_.ParameterType.Name }) -join ', ') + ')'
        }
        return "$($member.DeclaringType.FullName)::$($member.Name)$parameters"
    } catch {
        try { return '"' + $method.Module.ResolveString($token) + '"' } catch { }
        return ('token 0x{0:X8} (unresolved)' -f $token)
    }
}

# Writes one method's body, one instruction per line, as `//` comments.
function Write-ILListing($method) {
    $body = $null
    try { $body = $method.GetMethodBody() } catch { }
    if (-not $body) {
        Write-Output "//     (no body: abstract, extern or runtime-implemented)"
        return
    }
    $il = $body.GetILAsByteArray()
    Write-Output ("//     {0} bytes of IL, max stack {1}" -f $il.Length, $body.MaxStackSize)
    $i = 0
    while ($i -lt $il.Length) {
        $start = $i
        $code = [int]$il[$i]
        $i++
        if ($code -eq 0xFE) {
            $op = $script:ilTwoByte[[int]$il[$i]]
            $i++
        } else {
            $op = $script:ilOneByte[$code]
        }
        if (-not $op) {
            Write-Output ("//     IL_{0:X4}: unknown opcode 0x{1:X2}; listing stopped" -f $start, $code)
            return
        }
        $size = Get-ILOperandSize $op $il $i
        $operand = ''
        switch ($op.OperandType.ToString()) {
            'InlineNone' { }
            'ShortInlineBrTarget' {
                $b = [int]$il[$i]; if ($b -ge 128) { $b -= 256 }
                $operand = 'IL_{0:X4}' -f ($i + 1 + $b)
            }
            'ShortInlineI' { $b = [int]$il[$i]; if ($b -ge 128) { $b -= 256 }; $operand = "$b" }
            'ShortInlineVar' { $operand = "V_$($il[$i])" }
            'InlineVar' { $operand = "V_$([System.BitConverter]::ToUInt16($il, $i))" }
            'InlineI' { $operand = "$([System.BitConverter]::ToInt32($il, $i))" }
            'InlineI8' { $operand = "$([System.BitConverter]::ToInt64($il, $i))" }
            'InlineR' { $operand = [System.BitConverter]::ToDouble($il, $i).ToString('R', [System.Globalization.CultureInfo]::InvariantCulture) }
            'ShortInlineR' { $operand = [System.BitConverter]::ToSingle($il, $i).ToString('R', [System.Globalization.CultureInfo]::InvariantCulture) }
            'InlineBrTarget' { $operand = 'IL_{0:X4}' -f ($i + 4 + [System.BitConverter]::ToInt32($il, $i)) }
            'InlineSwitch' {
                $n = [System.BitConverter]::ToInt32($il, $i)
                $base = $i + 4 + 4 * $n
                $targets = @()
                for ($k = 0; $k -lt $n; $k++) { $targets += 'IL_{0:X4}' -f ($base + [System.BitConverter]::ToInt32($il, $i + 4 + 4 * $k)) }
                $operand = '(' + ($targets -join ', ') + ')'
            }
            default { $operand = Resolve-ILToken $method ([System.BitConverter]::ToInt32($il, $i)) }
        }
        $i += $size
        Write-Output ("//     IL_{0:X4}: {1} {2}" -f $start, $op.Name, $operand)
    }
}
