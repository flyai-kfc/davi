# Korean jamo key coordinates on the Samsung HoneyBoard 2-set QWERTY layout,
# measured from a 1080x2340 screenshot with the keyboard open.
$KB = @{
    'b'  = @(67, 1636);  'j' = @(172, 1636); 'd' = @(276, 1636); 'g' = @(381, 1636)
    's'  = @(486, 1636); 'yo' = @(591, 1636); 'yeo' = @(696, 1636); 'ya' = @(801, 1636)
    'ae' = @(907, 1636); 'e' = @(1011, 1636)
    'm'  = @(121, 1788); 'n' = @(225, 1788); 'ng' = @(329, 1788); 'r' = @(434, 1788)
    'h'  = @(538, 1788); 'o' = @(643, 1788); 'eo' = @(749, 1788); 'a' = @(854, 1788)
    'i'  = @(959, 1788)
    'k'  = @(225, 1939); 't' = @(329, 1939); 'ch' = @(434, 1939); 'p' = @(538, 1939)
    'yu' = @(643, 1939); 'u' = @(749, 1939); 'eu' = @(854, 1939)
    'space' = @(585, 2112); 'done' = @(984, 2112)
}

function TypeJamo {
    param([string[]]$Keys)
    foreach ($k in $Keys) {
        $c = $KB[$k]
        & $ADB -s $SERIAL shell input tap $c[0] $c[1] | Out-Null
        Start-Sleep -Milliseconds 120
    }
}
