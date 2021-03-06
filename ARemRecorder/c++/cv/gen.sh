#!/bin/bash

strindex() 
{ 
   x="${1%%$2*}"
      [[ $x = $1 ]] && echo -1 || echo ${#x}
}

S=`find ../../build/intermediates/classes/debug -iname "CV.class"`
#echo $S
I=`strindex "$S" "to/augmented/reality"`
CP=${S:0:I}
#echo $I $CP
rm to_augmented_reality_android_em_recorder_CV.h
javah -v -classpath "$CP" to.augmented.reality.android.em.recorder.CV
if [[ "$?" -ne 0 ]];
then
   echo -e "$?"": Include file generation failed. Try building first"
fi
