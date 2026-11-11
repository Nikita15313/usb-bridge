package local.usbbridge;

/** A late cleanup result belongs to one service generation, even after Stop. */
final class RouterUiState {
 private long generation;
 synchronized long begin(){return ++generation;}
 synchronized boolean publish(long owner,Runnable update){if(owner!=generation)return false;update.run();return true;}
 static String stage(String previous,String stage,String message){
  String[] fields=previous.split("\\|",7);StringBuilder result=new StringBuilder(stage).append('|').append(message);
  for(int i=2;i<fields.length;i++)result.append('|').append(fields[i]);
  return result.toString();
 }
 static String stopping(String previous){return stage(previous,"STOPPING","Завершаем отключение…");}
 static String completed(String result){
  if(result.startsWith("STOPPED"))return result;
  String[] fields=result.split("\\|",3);
  return stage(result,"ERROR",fields.length>1?fields[1]:"Не удалось подтвердить отключение");
 }
 static String failed(String previous){return stage(previous,"ERROR","Не удалось подтвердить отключение. Откройте приложение для проверки");}
}
